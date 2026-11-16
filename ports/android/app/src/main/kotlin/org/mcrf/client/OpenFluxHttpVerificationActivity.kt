package org.mcrf.client

import android.app.Activity
import android.content.res.Configuration
import android.os.*
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Private dedicated-profile HTTP entry. Source only: production HTTP capability
 * remains disabled. Intent carries ONLY opaque request ID, never URL/HTML/body.
 * One binding/worker; no process recreation, automatic OPEN or uncertain retry. */
class OpenFluxHttpVerificationActivity : Activity() {
    private enum class Phase { IDLE, OPENING, INITIAL, READY, RESOURCE, SUBMITTING, DONE }
    private class Step(val snapshot: OpenFluxHttpVerificationClient.Snapshot? = null, val id: String? = null,
        val response: OpenFluxHttpIpcResponse.Response? = null, val waiting: Boolean = false,
        val progress: Boolean = false, val ack: Int = 0)
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val admission = OpenFluxWindowAdmission()
    private val pending = ConcurrentHashMap.newKeySet<OpenFluxHttpIpcResponse.Response>()
    private var connection: OpenFluxHttpConnection? = null
    private var page: OpenFluxHttpPageView? = null
    @Volatile private var resumed = false
    private var phase = Phase.IDLE
    private var busy = false
    private var afterIdle: (() -> Unit)? = null
    private var initial: ((OpenFluxHttpPageView.Loaded?) -> Unit)? = null
    private var resource: ((OpenFluxHttpPageView.Exchange?) -> Unit)? = null
    private var submission: ((Boolean) -> Unit)? = null
    private var requestId = ""
    private fun live() = !closed.get() && resumed && !isFinishing && !isDestroyed && connection?.current() == true
    override fun onCreate(state: Bundle?) {
        UserThemeColorsUi.configure(this)
        val theme = getSharedPreferences("MainActivity", MODE_PRIVATE).getString("theme", "system")
        Loft.apply(theme == "dark" || theme == "system" && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (Loft.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        requestId = intent.getStringExtra("request").orEmpty()
        if (state != null || !requestId.matches(OpenFluxVerificationUiEvents.ID) || !OpenFluxVerificationWebProfile.ready) {
            finish(); return
        }
        setContentView(TextView(this).apply {
            text = "Подготовка изолированной страницы OpenFlux…\nСтраница откроется только по нажатию кнопки."
            setTextColor(Loft.INK); isSaveEnabled = false
        })
        val owner = OpenFluxHttpConnection(this, requestId, { ready() }, { fail("Подключение текущей проверки завершено.") })
        connection = owner
        owner.bind()
    }
    private fun ready() {
        if (closed.get() || isFinishing || isDestroyed) return
        try {
            val view = OpenFluxHttpPageView(this,
                current = { !closed.get() && connection?.current() == true },
                load = { done -> openDocument(done) },
                exchange = { request, done -> exchange(request, done) },
                submit = { payload, done -> send(payload, done) },
                failed = { message -> fail(message) }, cancelled = { finish() })
            page = view; setContentView(view)
            if (resumed) view.resumePage()
            handler.post(watch)
        } catch (_: Exception) { fail("Изолированная страница недоступна.") }
    }
    /** All action results retain independent byte ownership BEFORE completion
     * posting; close drains them even if queued UI callbacks are removed. */
    private fun step(permit: OpenFluxWindowAdmission.Permit?, action: (OpenFluxHttpVerificationClient) -> Step,
                     done: (Step) -> Unit) {
        if (!live() || !admission.current(permit) || busy) { fail("Проверка уже завершена или занята."); return }
        val owner = connection ?: return
        val result = AtomicReference<Step?>(); busy = true
        val accepted = owner.executeThen({ client ->
            check(!closed.get() && admission.current(permit))
            val value = action(client)
            value.response?.let(pending::add); result.set(value)
            if (closed.get() || !admission.current(permit)) {
                result.getAndSet(null)?.response?.let { pending.remove(it); it.close() }
                error("retired page")
            }
        }, {
            busy = false
            val value = result.getAndSet(null) ?: return@executeThen
            value.response?.let(pending::remove)
            if (!live() || !admission.current(permit)) { value.response?.close(); fail("Окно проверки больше не активно.") }
            else try { done(value) } catch (_: Exception) { value.response?.close(); fail("Проверка завершилась с ошибкой.") }
        })
        if (!accepted) { busy = false; fail("Операция проверки не принята. Повтор запрещён.") }
    }
    private fun openDocument(done: (OpenFluxHttpPageView.Loaded?) -> Unit) {
        if (live() && phase == Phase.IDLE && busy && afterIdle == null) {
            afterIdle = { openDocument(done) }; return // Manual Open waits once behind private idle POLL.
        }
        if (!live() || phase != Phase.IDLE) { done(null); return }
        val permit = admission.capture(); phase = Phase.OPENING; initial = done
        step(permit, { client ->
            check(client.foreground(true) && client.openAfterUserAction()); Step()
        }, { pollOpen(permit) })
    }
    private fun pollOpen(permit: OpenFluxWindowAdmission.Permit?) {
        step(permit, { client -> Step(snapshot = requireNotNull(client.poll())) }, { value ->
            val snapshot = requireNotNull(value.snapshot)
            when (snapshot.code) {
                1 -> handler.postDelayed({ if (!closed.get()) pollOpen(permit) }, 250)
                2 -> {
                    val address = requireNotNull(snapshot.address); phase = Phase.INITIAL
                    begin(permit, "GET", address, emptyList(), byteArrayOf()) { _, response ->
                        phase = Phase.READY
                        val callback = initial; initial = null
                        if (callback == null) response.close() else callback(OpenFluxHttpPageView.Loaded(address, response))
                    }
                }
                else -> error("invalid opening phase")
            }
        })
    }
    private fun begin(permit: OpenFluxWindowAdmission.Permit?, method: String, target: String,
                      headers: List<Pair<String, String>>, body: ByteArray,
                      done: (String, OpenFluxHttpIpcResponse.Response) -> Unit) {
        step(permit, { client -> Step(id = requireNotNull(client.enqueue(method, target, headers, body))) }, { value ->
            pull(permit, requireNotNull(value.id), done)
        })
    }
    private fun pull(permit: OpenFluxWindowAdmission.Permit?, id: String,
                     done: (String, OpenFluxHttpIpcResponse.Response) -> Unit) {
        step(permit, { client ->
            val value = client.pull(id)
            Step(response = value.response, waiting = value.waiting, progress = value.progress)
        }, { value ->
            val response = value.response
            if (response != null) {
                check(response.request == id); done(id, response)
            } else if (value.waiting) handler.postDelayed({ if (!closed.get()) pull(permit, id, done) }, if (value.progress) 0 else 250)
            else error("response retired")
        })
    }
    private fun exchange(request: OpenFluxHttpPageChannel.Request, done: (OpenFluxHttpPageView.Exchange?) -> Unit) {
        if (live() && phase == Phase.READY && busy && afterIdle == null) {
            afterIdle = { exchange(request, done) }; return // Exact once, behind an admitted private idle POLL.
        }
        if (!live() || phase != Phase.READY || busy) { done(null); return }
        val permit = admission.capture(); phase = Phase.RESOURCE; resource = done
        begin(permit, request.method, request.target, request.headers, request.body) { id, response ->
            phase = Phase.READY
            val callback = resource; resource = null
            if (callback == null) response.close() else callback(OpenFluxHttpPageView.Exchange(id, response))
        }
    }
    private fun send(payload: String, done: (Boolean) -> Unit) {
        if (live() && phase == Phase.READY && busy && afterIdle == null) {
            afterIdle = { send(payload, done) }; return // No false "busy" failure or replay of a native Send.
        }
        if (!live() || phase != Phase.READY || busy) { done(false); return }
        val permit = admission.capture(); phase = Phase.SUBMITTING; submission = done
        step(permit, { client -> check(client.submitAfterUserAction(payload)); Step() }, { pollReply(permit) })
    }
    private fun pollReply(permit: OpenFluxWindowAdmission.Permit?) {
        step(permit, { client -> Step(ack = client.replyStatus()) }, { value ->
            when (value.ack) {
                1 -> handler.postDelayed({ if (!closed.get()) pollReply(permit) }, 250)
                2 -> { phase = Phase.DONE; val callback = submission; submission = null; callback?.invoke(true) }
                else -> error("native reply not accepted")
            }
        })
    }
    private val watch = object : Runnable {
        override fun run() {
            if (closed.get() || !resumed) return
            if (connection?.current() != true) { fail("Запрос проверки истёк или завершён."); return }
            // Foreground private POLL only, never automatic OPEN/HTTP request.
            if ((phase == Phase.IDLE || phase == Phase.READY) && !busy) step(admission.capture(), { client -> Step(snapshot = requireNotNull(client.poll())) }, {
                val next = afterIdle; afterIdle = null; next?.invoke()
            })
            if (!closed.get()) handler.postDelayed(this, 500)
        }
    }
    private fun fail(message: String) {
        if (closed.get()) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish()
    }
    private fun retire() {
        if (!closed.compareAndSet(false, true)) return
        admission.close(); resumed = false; handler.removeCallbacksAndMessages(null)
        initial = null; resource = null; submission = null; afterIdle = null
        pending.toList().forEach { it.close() }; pending.clear()
        try { runCatching { page?.close() } } finally {
            page = null; runCatching { connection?.close() }; connection = null
        }
    }
    override fun onResume() {
        super.onResume(); resumed = true; admission.resume(); page?.resumePage()
        if (page != null) { handler.removeCallbacks(watch); handler.post(watch) }
    }
    override fun onPause() {
        resumed = false; admission.pause(); handler.removeCallbacks(watch)
        try { page?.pausePage() } finally { try { finish() } finally { super.onPause() } }
    }
    override fun finish() { try { retire() } finally { super.finish() } }
    override fun onDestroy() { try { retire() } finally { super.onDestroy() } }
}
