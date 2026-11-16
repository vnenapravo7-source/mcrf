package org.mcrf.client

import android.app.Activity
import android.content.*
import android.os.*
import android.widget.TextView
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Private offline own-page entry behind the disabled production capability.
 * One explicit native request; no recreation/read replay. */
class OpenFluxInlineVerificationActivity : Activity() {
    private val closed = AtomicBoolean(false)
    private val leaving = AtomicBoolean(false)
    private val attachQueued = AtomicBoolean(false)
    private val manualAdmission = OpenFluxWindowAdmission()
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    @Volatile private var resumed = false
    @Volatile private var remote: OpenFluxInlineVerificationClient? = null
    private var page: OpenFluxOwnPageView? = null
    private var id = ""
    private var bound = false
    private val pollQueued = AtomicBoolean(false)
    // The following fields belong ONLY to the single worker, never the UI thread.
    private var loadOwner: OpenFluxInlineVerificationClient? = null
    private var loadCompletion: ((String?) -> Unit)? = null
    private var pullTask: ScheduledFuture<*>? = null
    private var opened = false
    private val bindTimeout = Runnable { if (!closed.get() && remote == null) finish() }

    private fun active(owner: OpenFluxInlineVerificationClient) = !closed.get() && !leaving.get() && remote === owner && owner.current()
    private fun enqueue(action: () -> Unit): Boolean {
        if (closed.get() || leaving.get()) return false
        return try { worker.execute { if (!closed.get() && !leaving.get()) action() }; true } catch (_: RejectedExecutionException) { false }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed.get() || leaving.get() || !resumed) return
            val owner = remote
            if (owner != null && pollQueued.compareAndSet(false, true)) {
                if (!enqueue {
                    try {
                        owner.poll()
                        if (!active(owner)) handler.post { if (!closed.get()) finish() }
                    } finally { pollQueued.set(false) }
                }) pollQueued.set(false)
            }
            handler.postDelayed(this, 500)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, endpoint: IBinder) {
            if (isFinishing || isDestroyed || closed.get() || !attachQueued.compareAndSet(false, true)) return
            enqueue {
                val owner = try { OpenFluxInlineVerificationClient(endpoint, id) } catch (_: Exception) {
                    handler.post { if (!closed.get()) finish() }; return@enqueue
                }
                if (closed.get() || leaving.get()) { owner.close(); return@enqueue }
                remote = owner
                handler.post {
                    if (closed.get() || isFinishing || isDestroyed) return@post
                    handler.removeCallbacks(bindTimeout)
                    val view = OpenFluxOwnPageView(this@OpenFluxInlineVerificationActivity,
                        current = { active(owner) },
                        loadVerifiedDocument = { completion -> startLoad(owner, completion) },
                        offerSubmission = { payload, stillCurrent, done ->
                            val permit = manualAdmission.capture()
                            if (!enqueue { done(manualAdmission.current(permit) && active(owner) && stillCurrent() && owner.submitAfterUserAction(payload)) }) done(false)
                        },
                        failed = { message ->
                            android.widget.Toast.makeText(this@OpenFluxInlineVerificationActivity, message, android.widget.Toast.LENGTH_LONG).show()
                            finish()
                        },
                        cancelled = { finish() })
                    page = view; setContentView(view)
                    if (resumed) view.resumePage() else view.pausePage()
                    if (resumed) { handler.removeCallbacks(poll); handler.post(poll) }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { finish() }
        override fun onBindingDied(name: ComponentName) { finish() }
        override fun onNullBinding(name: ComponentName) { finish() }
    }
    override fun onCreate(state: Bundle?) {
        UserThemeColorsUi.configure(this)
        val theme = getSharedPreferences("MainActivity", MODE_PRIVATE).getString("theme", "system")
        Loft.apply(theme == "dark" || theme == "system" && resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (Loft.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        id = intent.getStringExtra("request").orEmpty()
        if (state != null || !OpenFluxVerificationWebProfile.ready || !id.matches(OpenFluxVerificationClient.ID)) { finish(); return }
        setContentView(TextView(this).apply {
            text = "Подготовка встроенной проверки OpenFlux…\nУход из окна отменит незавершённую проверку."
            setTextColor(Loft.INK); isSaveEnabled = false
        })
        val intent = Intent(this, OpenFluxVerificationService::class.java)
            .setData(android.net.Uri.parse("mcrf-openflux-inline-bind://request/$id/${java.util.UUID.randomUUID()}"))
            .putExtra("request", id)
        bound = try { bindService(intent, connection, BIND_AUTO_CREATE) } catch (_: Exception) { false }
        if (!bound) finish() else handler.postDelayed(bindTimeout, 10_000)
    }
    private fun startLoad(owner: OpenFluxInlineVerificationClient, completion: (String?) -> Unit) {
        val permit = manualAdmission.capture()
        if (!enqueue {
            if (!manualAdmission.current(permit) || opened || !active(owner)) { completion(null); return@enqueue }
            opened = true
            if (!owner.beginAfterUserOpen()) { completion(null); return@enqueue }
            loadOwner = owner; loadCompletion = completion; schedulePull(0)
        }) completion(null)
    }
    private fun schedulePull(delay: Long) {
        if (!resumed || closed.get() || leaving.get() || loadCompletion == null || pullTask != null) return
        try { pullTask = worker.schedule({ pullTask = null; pull() }, delay, TimeUnit.MILLISECONDS) }
        catch (_: RejectedExecutionException) { completeLoad(null) }
    }
    private fun pull() {
        if (!resumed || closed.get()) return
        val owner = loadOwner ?: return
        if (!active(owner)) { completeLoad(null); return }
        val value = owner.pull()
        when {
            value.html != null -> completeLoad(value.html)
            value.waiting -> schedulePull(if (value.progress) 0 else 250)
            else -> completeLoad(null)
        }
    }
    private fun completeLoad(html: String?) {
        val callback = loadCompletion
        loadCompletion = null; loadOwner = null
        pullTask?.cancel(false); pullTask = null
        if (!closed.get()) callback?.invoke(html) // View posts and rechecks its exact owner before rendering.
    }
    override fun onResume() {
        super.onResume(); manualAdmission.resume(); resumed = true; page?.resumePage()
        handler.removeCallbacks(poll); handler.post(poll)
        enqueue { schedulePull(0) }
    }
    override fun onPause() {
        manualAdmission.pause(); resumed = false; handler.removeCallbacks(poll); page?.pausePage()
        enqueue { pullTask?.cancel(false); pullTask = null }
        super.onPause()
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) finish() // No hidden page/transfer or read replay.
    }
    override fun finish() { manualAdmission.close(); leaving.set(true); super.finish() }
    override fun onDestroy() {
        manualAdmission.close(); closed.set(true); handler.removeCallbacksAndMessages(null)
        page?.close(); page = null
        try { worker.execute {
            try {
                pullTask?.cancel(false); pullTask = null; loadCompletion = null; loadOwner = null
                remote?.close(); remote = null
            } finally { worker.shutdown() } // Do not let shutdown policy discard its own queued cleanup.
        } } catch (_: RejectedExecutionException) {
            worker.shutdown() // Exact endpoint is also released by unbind below.
        }
        if (bound) { runCatching { unbindService(connection) }; bound = false }
        if (remote == null) OpenFluxVerificationUi.failedOpening(this, id)
        super.onDestroy()
    }
}
