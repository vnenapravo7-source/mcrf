package org.mcrf.client

import java.util.concurrent.TimeUnit

/** Returns only after the OS confirms exit, so the enclosing module usage lease remains valid. */
object WarpChildProcess {
    fun stop(process: Process, reader: Thread? = null, graceMillis: Long = 3000) {
        var interrupted = Thread.interrupted()
        try {
            if(process.isAlive) {
                try {
                    process.outputStream.write("STOP\n".toByteArray(Charsets.US_ASCII))
                    process.outputStream.flush()
                    process.outputStream.close()
                    if(!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                } catch(_: InterruptedException) { interrupted = true; process.destroyForcibly() }
                catch(_: Exception) { process.destroyForcibly() }
            }
            while(process.isAlive) {
                process.destroyForcibly()
                try { process.waitFor() } catch(_: InterruptedException) { interrupted = true }
            }
            listOf(process.inputStream, process.outputStream, process.errorStream).forEach { try { it.close() } catch(_: Exception) { } }
            try { reader?.join(1000) } catch(_: InterruptedException) { interrupted = true }
        } finally { if(interrupted) Thread.currentThread().interrupt() }
    }
}
