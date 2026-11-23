package org.mcrf.client

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

data class DiagnosticsWorkResult<T>(val state: DiagnosticState, val value: T? = null, val issue: DiagnosticIssue = DiagnosticIssue.NONE)
internal class DiagnosticsPathChanged : java.io.IOException()

/** One bounded worker and queue per screen. Watchdog completion is independent of
 * a provider draining a noninterruptible native call. No global cancel/stop. */
class ServiceDiagnosticsWork : AutoCloseable {
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        ThreadFactory { Thread(it, "mcrf-diagnostics-job").apply { isDaemon = true } })
    private val timer = ScheduledThreadPoolExecutor(1) { Thread(it, "mcrf-diagnostics-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    @Volatile private var active: AutoCloseable? = null
    @Synchronized fun <T> start(timeoutMs: Long = 30_000, action: (DiagnosticCancellation) -> T, complete: (DiagnosticsWorkResult<T>) -> Unit): AutoCloseable {
        require(timeoutMs in 1..900_000)
        check(!closed.get() && busy.compareAndSet(false, true))
        val token = DiagnosticCancellation(timeoutMs)
        val finished = AtomicBoolean()
        var future: Future<*>? = null
        var expiry: ScheduledFuture<*>? = null
        fun finish(value: DiagnosticsWorkResult<T>): Unit = synchronized(this@ServiceDiagnosticsWork) {
            if (finished.compareAndSet(false, true)) {
                expiry?.cancel(false); active = null; busy.set(false)
                runCatching { complete(value) }
            }
        }
        val handle = AutoCloseable { synchronized(this@ServiceDiagnosticsWork) {
            token.cancel(); future?.cancel(true)
            finish(DiagnosticsWorkResult(DiagnosticState.CANCELLED, issue = DiagnosticIssue.CANCELLED))
        } }
        active = handle
        try {
            expiry = timer.schedule({ synchronized(this@ServiceDiagnosticsWork) {
                token.cancel(); future?.cancel(true)
                finish(DiagnosticsWorkResult(DiagnosticState.ERROR, issue = DiagnosticIssue.TIMEOUT))
            } }, timeoutMs, TimeUnit.MILLISECONDS)
            future = worker.submit {
                try {
                    token.check()
                    val result = action(token)
                    token.check()
                    finish(DiagnosticsWorkResult(DiagnosticState.AVAILABLE, result))
                } catch (_: CancellationException) {
                    finish(DiagnosticsWorkResult(DiagnosticState.CANCELLED, issue = DiagnosticIssue.CANCELLED))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    finish(DiagnosticsWorkResult(DiagnosticState.CANCELLED, issue = DiagnosticIssue.CANCELLED))
                } catch (_: java.net.SocketTimeoutException) {
                    finish(DiagnosticsWorkResult(DiagnosticState.ERROR, issue = DiagnosticIssue.TIMEOUT))
                } catch (_: DiagnosticsPathChanged) {
                    finish(DiagnosticsWorkResult(DiagnosticState.UNAVAILABLE, issue = DiagnosticIssue.PATH_CHANGED))
                } catch (_: Exception) {
                    finish(DiagnosticsWorkResult(DiagnosticState.ERROR, issue = DiagnosticIssue.INTERNAL))
                } finally { token.cancel(); worker.purge() }
            }
        } catch (_: RejectedExecutionException) {
            token.cancel(); finish(DiagnosticsWorkResult(DiagnosticState.UNAVAILABLE, issue = DiagnosticIssue.CAPACITY))
        }
        if (closed.get()) handle.close()
        return handle
    }
    @Synchronized override fun close() {
        if (closed.compareAndSet(false, true)) { active?.close(); timer.shutdownNow(); worker.shutdownNow() }
    }
}
