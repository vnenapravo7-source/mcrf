package org.mcrf.client

import android.content.*
import android.net.Uri
import android.os.*
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/** Private HTTP binding owner, not an Activity or production capability switch.
 * Main thread owns framework binding/callbacks. ONE worker owns client/RPCs.
 * No automatic OPEN, foreground, polling, requests, retry or reconnect.
 * Caller must close on window retirement and recheck its UI epoch before use. */
internal class OpenFluxHttpConnection(
    context: Context,
    private val id: String,
    ready: () -> Unit,
    failed: () -> Unit
) : AutoCloseable {
    private val base = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val attached = AtomicBoolean(false)
    private val taskQueued = AtomicBoolean(false)
    private val cleanupQueued = AtomicBoolean(false)
    private val worker = ScheduledThreadPoolExecutor(1) { action ->
        Thread(action, "mcrf-openflux-http-client").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    @Volatile private var remote: OpenFluxHttpVerificationClient? = null
    // Main-thread only; callbacks are dropped on retirement to release Activity references.
    private var onReady: (() -> Unit)? = ready
    private var onFailed: (() -> Unit)? = failed
    private var started = false
    private var bindingAttempted = false
    private val timeout = Runnable { if (!closed.get() && remote == null) retire(failure = true) }
    init { mainOnly(); require(id.matches(OpenFluxVerificationUiEvents.ID)) }
    private fun mainOnly() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, endpoint: IBinder) {
            mainOnly()
            if (closed.get() || !attached.compareAndSet(false, true)) return
            try {
                worker.execute {
                    if (closed.get()) return@execute
                    val owner = try { OpenFluxHttpVerificationClient(endpoint, id) { !closed.get() } }
                        catch (_: Exception) { failFromWorker(); return@execute }
                    // Constructor may have overlapped timeout/close before publication.
                    if (closed.get()) { owner.signalClose(); owner.close(); return@execute }
                    remote = owner
                    // Close may have missed the just-published reference; both checks are needed.
                    if (closed.get()) { owner.signalClose(); return@execute }
                    handler.post {
                        if (!closed.get() && remote === owner && owner.current()) {
                            handler.removeCallbacks(timeout)
                            onReady?.invoke()
                        } else if (!closed.get()) retire(failure = true)
                    }
                }
            } catch (_: RejectedExecutionException) { retire(failure = true) }
        }
        override fun onServiceDisconnected(name: ComponentName) { retire(failure = true) }
        override fun onBindingDied(name: ComponentName) { retire(failure = true) }
        override fun onNullBinding(name: ComponentName) { retire(failure = true) }
    }
    /** Exactly one bind attempt per owner. Framework calls remain on main. */
    fun bind(): Boolean {
        mainOnly()
        if (started || closed.get()) return false
        started = true
        val intent = Intent(base, OpenFluxHttpVerificationService::class.java)
            .setData(Uri.parse("mcrf-openflux-http-bind://request/$id/${UUID.randomUUID()}"))
            .putExtra("request", id)
        bindingAttempted = true
        val accepted = try { base.bindService(intent, connection, Context.BIND_AUTO_CREATE) } catch (_: Exception) { false }
        if (!accepted) { retire(failure = true); return false }
        if (!closed.get()) handler.postDelayed(timeout, 10_000)
        return !closed.get()
    }
    fun current(): Boolean = !closed.get() && remote?.current() == true
    fun snapshot(): OpenFluxHttpVerificationClient.Snapshot? = if (closed.get()) null else remote?.snapshot()
    /** At most ONE caller task queued/running, rather than an unbounded UI/JS
     * executor queue. Rejected work is never replayed. Action stays off-main;
     * it must close any response it owns and post only epoch-checked UI output. */
    fun execute(action: (OpenFluxHttpVerificationClient) -> Unit): Boolean = executeThen(action, {})
    /** Completion is posted ONLY after releasing the caller-task slot. Posting
     * from inside action could let UI attempt the next step before finally ran.
     * Caller owns any result bytes independently of this disposable callback. */
    fun executeThen(action: (OpenFluxHttpVerificationClient) -> Unit, completed: () -> Unit): Boolean {
        mainOnly()
        val owner = remote ?: return false
        if (!current() || !taskQueued.compareAndSet(false, true)) return false
        return try {
            worker.execute {
                var ran = false
                try {
                    if (!closed.get() && remote === owner && owner.current()) {
                        action(owner); ran = true
                        if (!owner.current()) failFromWorker()
                    }
                } catch (_: Exception) { failFromWorker() }
                finally { taskQueued.set(false) }
                if (ran && !closed.get()) handler.post {
                    if (!closed.get() && remote === owner) {
                        if (!owner.current()) retire(failure = true)
                        else try { completed() } catch (_: Exception) { retire(failure = true) }
                    }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            taskQueued.set(false); retire(failure = true); false
        }
    }
    private fun failFromWorker() {
        remote?.signalClose() // Atomic admission stop; never wait for another RPC monitor.
        handler.post { retire(failure = true) }
    }
    private fun retire(failure: Boolean) {
        mainOnly()
        if (!closed.compareAndSet(false, true)) return
        remote?.signalClose()
        handler.removeCallbacks(timeout)
        val callback = if (failure) onFailed else null
        onFailed = null; onReady = null
        // Release the exact framework binding independently of an admitted RPC.
        // No main-thread client.close/transact or waiting for worker termination.
        if (bindingAttempted) { bindingAttempted = false; runCatching { base.unbindService(connection) } }
        if (cleanupQueued.compareAndSet(false, true)) {
            try {
                worker.execute {
                    try { remote?.close() }
                    finally { remote = null; worker.shutdown() }
                }
            } catch (_: RejectedExecutionException) {
                // No replacement worker and no UI RPC fallback. Framework unbind
                // above already releases the endpoint; cannot claim remote exit.
                worker.shutdown()
            }
        }
        callback?.invoke()
    }
    override fun close() { mainOnly(); retire(failure = false) }
    override fun toString() = "OpenFluxHttpConnection(redacted)"
}
