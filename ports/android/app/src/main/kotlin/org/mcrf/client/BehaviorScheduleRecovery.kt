package org.mcrf.client

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import org.json.JSONObject
import java.time.Instant

/** Reuses the app's platform JobScheduler. A periodic safety job survives a missing one-shot rearm. */
internal object BehaviorScheduleRecovery {
    const val INTERVAL=15*60_000L
    private val lock=Any()
    private val running=mutableSetOf<Int>()
    internal fun configured(preferences:JSONObject):Boolean {
        val schedule=preferences.optJSONObject(BehaviorSchedule.KEY)?.let(BehaviorSchedule::fromJson)
        val section=preferences.optJSONObject(ConditionProfiles.KEY)
        val profiles=section?.let(ConditionProfiles::fromJson).orEmpty()
        val active=section?.optString("activeId").orEmpty()
        return schedule?.let {it.enabled&&it.rules.any {rule->rule.enabled}}==true||profiles.any {it.enabled}||
            active.isNotEmpty()&&active!=NetworkProfileSwitch.DEFAULT
    }
    internal fun latency(now:Long,due:Long):Long=if(due<=now)0L else
        runCatching {Math.subtractExact(due,now)}.getOrDefault(Long.MAX_VALUE).coerceAtMost(8*24*3_600_000L)
    private fun needed(context:Context)=synchronized(ProfileStore.storageLock) {
        configured(JSONObject(ProfileStore(context,"preferences.enc").read().ifBlank {"{}"}))
    }
    fun jobStarted(id:Int)=synchronized(lock){running.add(id);Unit}
    fun jobEnded(id:Int)=synchronized(lock){running.remove(id);Unit}
    fun schedule(context:Context,next:Instant?,immediate:Boolean=false) {
        val enabled=needed(context)
        val scheduler=context.getSystemService(JobScheduler::class.java)
        val component=ComponentName(context,BehaviorScheduleJobService::class.java)
        synchronized(lock) {
            if(!enabled) {scheduler.cancel(BackgroundJobIds.BEHAVIOR_RECOVERY);scheduler.cancel(BackgroundJobIds.BEHAVIOR_NEXT);return}
            val periodic=scheduler.getPendingJob(BackgroundJobIds.BEHAVIOR_RECOVERY)
            if(BackgroundJobIds.BEHAVIOR_RECOVERY !in running&&
                (periodic==null||periodic.service!=component||periodic.intervalMillis!=INTERVAL||!periodic.isPersisted)) {
                check(scheduler.schedule(JobInfo.Builder(BackgroundJobIds.BEHAVIOR_RECOVERY,component)
                    .setPersisted(true).setPeriodic(INTERVAL,5*60_000L)
                    .setBackoffCriteria(30_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())==JobScheduler.RESULT_SUCCESS)
            }
            // Replacing a running job would stop it. Its completion callback rearms after jobFinished.
            if(BackgroundJobIds.BEHAVIOR_NEXT in running)return
            val now=System.currentTimeMillis()
            val due=if(immediate)now else next?.toEpochMilli()
            val existing=scheduler.getPendingJob(BackgroundJobIds.BEHAVIOR_NEXT)
            if(due==null) {if(existing!=null)scheduler.cancel(BackgroundJobIds.BEHAVIOR_NEXT);return}
            // Keep an already queued recovery instead of postponing it on every foreground evaluation.
            if(existing!=null&&existing.service==component&&existing.isPersisted&&
                (existing.extras.getBoolean("immediate",false)||!immediate&&existing.extras.getLong("due",Long.MAX_VALUE)==due))return
            check(scheduler.schedule(JobInfo.Builder(BackgroundJobIds.BEHAVIOR_NEXT,component)
                .setPersisted(true).setMinimumLatency(latency(now,due)).setExtras(PersistableBundle().apply {putLong("due",due);putBoolean("immediate",immediate)})
                .setBackoffCriteria(30_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())==JobScheduler.RESULT_SUCCESS)
        }
    }
    fun recover(context:Context) {schedule(context,null,immediate=true);BehaviorScheduleRuntime.start(context)}
}

/** Both job IDs may run in one service instance; ownership and cancellation are per JobParameters. */
class BehaviorScheduleJobService:JobService() {
    private val main=Handler(Looper.getMainLooper())
    private val runs=BehaviorScheduleJobRuns<JobParameters>()
    override fun onStartJob(params:JobParameters):Boolean {
        if(params.jobId !in setOf(BackgroundJobIds.BEHAVIOR_RECOVERY,BackgroundJobIds.BEHAVIOR_NEXT))return false
        val run=runs.start(params.jobId,params)
        BehaviorScheduleRecovery.jobStarted(params.jobId)
        BehaviorScheduleRuntime.runScheduled(applicationContext,{run.cancelled.get()}) {retry->main.post {
            if(!runs.finish(params.jobId,run))return@post
            jobFinished(params,retry)
            BehaviorScheduleRecovery.jobEnded(params.jobId)
            BehaviorScheduleRuntime.rearmRecovery(applicationContext)
        }}
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        if(runs.stop(params.jobId))BehaviorScheduleRecovery.jobEnded(params.jobId)
        return true
    }
    override fun onDestroy() {
        runs.destroy().forEach(BehaviorScheduleRecovery::jobEnded);super.onDestroy()
    }
}
