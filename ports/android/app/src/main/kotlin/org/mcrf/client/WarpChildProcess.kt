package org.mcrf.client

import java.util.concurrent.TimeUnit

/** Returns only after the OS confirms exit, so the enclosing module usage lease remains valid. */
object WarpChildProcess {
    fun stop(process: Process, reader: Thread? = null, graceMillis: Long = 3000) {
        require(graceMillis in 0..30000)
        var interrupted = Thread.interrupted()
        val ipc=if(process.isAlive)CarrierIpcWriter(process.outputStream)else null
        try {
            if(process.isAlive) {
                try {
                    ipc!!.requestStop()
                    if(!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                } catch(_: InterruptedException) { interrupted = true; process.destroyForcibly() }
                catch(_: Exception) { process.destroyForcibly() }
            }
            while(process.isAlive) {
                process.destroyForcibly()
                try { process.waitFor() } catch(_: InterruptedException) { interrupted = true }
            }
            ipc?.childExited()
            while(ipc!=null&&!ipc.awaitCompletion(0).writerExited) {
                try{ipc.awaitCompletion(100)}catch(_:InterruptedException){interrupted=true}
            }
            listOf(process.inputStream, process.outputStream, process.errorStream).forEach { try { it.close() } catch(_: Exception) { } }
            try { reader?.join(1000) } catch(_: InterruptedException) { interrupted = true }
        } finally { if(interrupted) Thread.currentThread().interrupt() }
    }
}
