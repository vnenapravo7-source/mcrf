package org.mcrf.client

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper

object EngineUpdateSchedule {
    const val JOB = 2402
    const val INTERVAL = 6 * 3_600_000L
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!EngineUpdates.automaticEligible(context)) { scheduler.cancel(JOB); return }
        if (scheduler.getPendingJob(JOB)?.intervalMillis == INTERVAL) return
        check(scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, EngineUpdateService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setPeriodic(INTERVAL, 3_600_000L).setBackoffCriteria(30 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()) == JobScheduler.RESULT_SUCCESS)
        // First installation/explicit enable starts discovery now; existing jobs keep their cadence.
        EngineUpdates.check(context,true)
    }
}

/** Discovery/download only. Android consent is queued for foreground UI. */
class EngineUpdateService : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    override fun onStartJob(params: JobParameters): Boolean {
        if (!EngineUpdates.automaticEligible(this)) return false
        if (!EngineUpdates.check(this, true)) return false
        val token = ++generation
        handler.post(object : Runnable {
            override fun run() {
                if (token != generation) return
                val phase = EngineUpdates.snapshot(this@EngineUpdateService).phase
                if (EngineUpdates.isBusy()) handler.postDelayed(this, 1000)
                else jobFinished(params, phase == EngineUpdatePolicy.Phase.ERROR)
            }
        })
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { generation++; EngineUpdates.stopDownload(); return EngineUpdates.automatic(this) }
    override fun onDestroy() { generation++; handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
