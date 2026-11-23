package org.mcrf.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation is nonblocking. Retirement retains every late startup resource
 * and the exclusive lease until actual cleanup, including failed close retries. */
internal class DiagnosticsProxyLifetime(private val timeoutMs: Long, private val lease: AutoCloseable) : AutoCloseable {
    private val lock = Any()
    private val startup = CountDownLatch(1)
    private val retired = CountDownLatch(1)
    private val stopping = AtomicBoolean()
    private val resources = mutableListOf<AutoCloseable>()
    private val stops = mutableListOf<() -> Unit>()
    @Volatile private var expiry: java.util.concurrent.ScheduledFuture<*>? = null
    private var expiryGeneration = 0L
    init {
        require(timeoutMs in 1..120_000)
        refresh()
    }
    /** Idle watchdog, not a two-minute cap on a multi-target server run. A stale
     * queued timer must not retire a freshly renewed owner or resurrect a stop. */
    fun refresh(): Boolean = synchronized(lock) {
        if (stopping.get()) return@synchronized false
        val generation = ++expiryGeneration
        expiry?.cancel(false)
        expiry = timer.schedule({ synchronized(lock) { if (generation == expiryGeneration) close() } }, timeoutMs, TimeUnit.MILLISECONDS)
        true
    }
    fun isStopped() = stopping.get()
    fun <T : AutoCloseable> adopt(resource: T, stop: () -> Unit = {}): T {
        val stopped = synchronized(lock) {
            check(startup.count != 0L)
            resources.add(resource); stops.add(stop); stopping.get()
        }
        if (stopped) runCatching(stop)
        return resource
    }
    fun startupFinished() { startup.countDown() }
    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        expiry?.cancel(false)
        synchronized(lock) { stops.toList() }.forEach { runCatching(it) }
        cleanup.execute {
            var interrupted = false
            while (true) {
                try { startup.await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            val owned = synchronized(lock) { resources.asReversed().toList() }
            owned.forEach { OptionalCoreRetirement.drain(it) }
            OptionalCoreRetirement.drain(lease)
            retired.countDown()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    /** Worker only. Never admit the next server while this owner's child lives. */
    fun awaitClosed() {
        close()
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                try { retired.await(); return } catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    companion object {
        private val timer = ScheduledThreadPoolExecutor(1) { Thread(it, "mcrf-diagnostic-proxy-timeout").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
        private val cleanup = Executors.newCachedThreadPool { Thread(it, "mcrf-diagnostic-proxy-cleanup").apply { isDaemon = true } }
    }
}
