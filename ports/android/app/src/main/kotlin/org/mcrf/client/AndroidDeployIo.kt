package org.mcrf.client

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** UI persistence is ordered across pages. Leaving a page drops delivery, never an accepted write. */
internal object AndroidDeployIo {
    private val worker=Executors.newSingleThreadExecutor { task->Thread(task,"deploy-storage") }
    val main=Handler(Looper.getMainLooper())
    fun <T> submit(work:()->T,deliver:(Result<T>)->Unit) {
        worker.execute {
            val result=runCatching(work)
            main.post {deliver(result)}
        }
    }
}
