package org.mcrf.client

import java.util.concurrent.TimeUnit

/** Blocking retirement of one exact owned child. Call on the retiring worker,
 * never the UI thread. A timeout, failed kill or interruption is not death.
 * In particular, start's failure path must keep waiting here before losing its
 * local owner. The injected pause makes retry/death ordering host-testable.
 */
internal class ClientCarrierRetirement(
    private val child: Process,
    private val graceSeconds: Long,
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) : AutoCloseable {
    @Volatile var requested = false
        private set
    @Volatile var complete = false
        private set
    @Volatile var forced = false
        private set

    val normalExit: Boolean
        get() = complete && !forced && try { child.exitValue() in 0..1 } catch (_: Exception) { false }

    @Synchronized override fun close() {
        if (complete) return
        requested = true
        var interrupted = Thread.interrupted()
        fun operation(action: () -> Unit) {
            try { action() }
            catch (_: InterruptedException) { interrupted = true; Thread.interrupted() }
            catch (_: Exception) { /* Retain this child and retry; never acknowledge. */ }
        }
        fun dead(): Boolean = try { !child.isAlive } catch (_: Exception) { false }
        try {
            if (!dead()) {
                operation {
                    child.outputStream.write("STOP\n".toByteArray(Charsets.US_ASCII))
                    child.outputStream.flush()
                    child.outputStream.close()
                    child.waitFor(graceSeconds, TimeUnit.SECONDS)
                }
                if (!dead()) {
                    forced = true // Sticky even if destroy throws or death is delayed.
                    operation { child.destroy(); child.waitFor(1, TimeUnit.SECONDS) }
                }
            }
            var delay = 100L
            while (!dead()) {
                forced = true
                operation { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) }
                if (!dead()) {
                    operation { pause(delay) }
                    delay = (delay * 2).coerceAtMost(3200L)
                }
            }
            complete = true
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
