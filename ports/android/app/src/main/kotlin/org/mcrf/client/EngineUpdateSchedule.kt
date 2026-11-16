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
    const val JOB = BackgroundJobIds.ENGINES
    const val INTERVAL = 6 * 3_600_000L
    private var attempted=false
    @Synchronized fun schedule(context:Context) {
        // Retire the old periodic job on upgrade. Restoring an Activity is not a launch.
        context.getSystemService(JobScheduler::class.java).cancel(JOB)
        if(attempted)return
        attempted=true
        if(EngineUpdates.automaticEligible(context))EngineUpdates.check(context,true)
    }

}

/** Kept to retire persisted jobs from older app versions without scheduling another run. */
class EngineUpdateService : JobService() {
    override fun onStartJob(params:JobParameters):Boolean=false
    override fun onStopJob(params:JobParameters):Boolean=false
}
