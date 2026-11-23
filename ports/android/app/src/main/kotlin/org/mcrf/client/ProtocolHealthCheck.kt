package org.mcrf.client

/** Retain the owned transport's safe cause when outer HTTPS/DNS throws. Trust
 * denial must stay a SecurityException, cancellation must not become I/O. */
internal object ProtocolHealthCheck {
    data class TcpSample(val pingMs: Long?, val failures: Int)

    fun measureHealth(store: HealthStore, serverId: String, network: HealthNetwork,
                      cancellation: HealthCancellation, measure: () -> TcpSample): Boolean {
        cancellation.check()
        val permit = store.reserve() ?: return false
        cancellation.check()
        val result = measure()
        return cancellation.publish {
            store.recordSample(serverId, permit, result.pingMs, result.failures, network)
        }
    }

    /** Exactly three bounded attempts; cancellation is never recorded as loss. */
    fun sample(cancellation: HealthCancellation, attempt: () -> Long?): TcpSample {
        val delays = ArrayList<Long>()
        var failures = 0
        repeat(HealthSettings.ATTEMPTS) {
            cancellation.check()
            val delay = try { attempt() } catch (error: java.io.IOException) {
                cancellation.check(); null
            }
            cancellation.check()
            if (delay == null) failures++ else {
                require(delay in 0..HealthSettings.TIMEOUT_MS.toLong())
                delays.add(delay)
            }
        }
        cancellation.check()
        delays.sort()
        return TcpSample(delays.getOrNull(delays.size / 2), failures)
    }

    /** Connect only: no TLS, HTTP, profile, authentication or application payload. */
    fun tcpPing(address: java.net.InetSocketAddress, cancellation: HealthCancellation,
                bind: (java.net.Socket) -> Unit): Long? {
        cancellation.check()
        return java.net.Socket().use { socket ->
            val ownership = cancellation.attach { socket.close() }
            try {
                cancellation.check(); bind(socket); cancellation.check()
                val start = System.nanoTime()
                socket.connect(address, HealthSettings.TIMEOUT_MS)
                cancellation.check()
                ((System.nanoTime() - start) / 1_000_000).coerceIn(0, HealthSettings.TIMEOUT_MS.toLong())
            } finally { ownership.close() }
        }
    }

    fun verify(probe:()->Boolean,detail:()->Exception):Boolean {
        val healthy=try{probe()}catch(error:Exception){
            if(error is InterruptedException||error is java.util.concurrent.CancellationException)throw error
            val native=detail()
            val message=native.message.orEmpty()+"\nПроверка передачи: ${error.javaClass.simpleName}: "+ClientEvents.redact(error.message).take(600)
            throw if(native is SecurityException||error is SecurityException)SecurityException(message,error)
                else java.io.IOException(message,error)
        }
        if(!healthy)throw detail()
        return true
    }
}

/** Closing a page or revoking consent promptly closes owned DNS/socket resources. */
internal class HealthCancellation {
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean()
    private val closers = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted)
            throw java.util.concurrent.CancellationException("HEALTH_CANCELLED")
    }
    fun attach(close: () -> Unit): AutoCloseable {
        closers.add(close)
        if (cancelled.get()) { closers.remove(close); runCatching(close); check() }
        return AutoCloseable { closers.remove(close) }
    }
    fun cancel() {
        synchronized(this) { cancelled.set(true) }
        closers.forEach { runCatching(it) }
        closers.clear()
    }
    fun <T> publish(action: () -> T): T = synchronized(this) { check(); action() }
}
