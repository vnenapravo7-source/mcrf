package org.mcrf.client

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** No provider I/O on UI/BroadcastReceiver threads. No unbounded proof queue.
 * Acceptance means scheduled only, NOT native delivery or transport success. */
internal class CarrierChallengeDelivery : AutoCloseable {
    private val executor=ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,SynchronousQueue<Runnable>(),{task->
        Thread(task,"mcrf-captcha-reply").apply {isDaemon=true}
    },ThreadPoolExecutor.AbortPolicy())
    fun offer(action:()->Unit):Boolean = try {
        executor.execute {try {action()}catch(_:Exception){}}
        true
    }catch(_:RejectedExecutionException){false}
    /** Tests/owner shutdown only; never waits for a blocked native pipe. */
    override fun close(){executor.shutdown()}
}
