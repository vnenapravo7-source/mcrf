package org.mcrf.client

/** Kernel-release errors must retain an active retry owner even if a failed
 * preflight/installer caller unwinds without ever receiving its lease. The
 * direct operation is idempotent and ACKs only after real channel release.
 * Normal close remains synchronous/nonblocking; failure is still propagated. */
internal class RetryingEngineRelease(private val release:()->Unit):AutoCloseable {
    private val retained=java.util.concurrent.atomic.AtomicBoolean()
    override fun close() {
        try {release()}
        catch(error:Exception) {
            if(retained.compareAndSet(false,true)) {
                val retirement=ClientPrimaryRetirement(Unit,{}, {},{release()},emptyList(),{})
                Thread({
                    var interrupted=Thread.interrupted()
                    try {while(!retirement.attempt()) {
                        if(Thread.interrupted())interrupted=true
                        try{Thread.sleep(retirement.retryDelayMillis())}catch(_:InterruptedException){interrupted=true}
                    }}finally{if(interrupted)Thread.currentThread().interrupt()}
                },"mcrf-engine-release").apply {isDaemon=true;start()}
            }
            throw error
        }
    }
}
