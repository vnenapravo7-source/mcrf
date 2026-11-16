package org.mcrf.client

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Best-effort ordered diagnostics only. A stalled disk cannot block a WebView callback. */
internal class CarrierCaptchaLogDelivery:AutoCloseable {
    private val worker=ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(32),
        {task->Thread(task,"mcrf-captcha-log").apply {isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    fun offer(action:()->Unit):Boolean=try {
        worker.execute {try{action()}catch(_:Exception){/* Diagnostics cannot change challenge lifecycle. */}}
        true
    }catch(_:RejectedExecutionException){false}
    /** Does not wait for the disk or cancel already queued diagnostic records. */
    override fun close(){worker.shutdown()}
}
