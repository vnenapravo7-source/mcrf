package org.mcrf.client

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle

/** One overdue app-open request, never replacing/postponing the periodic job. */
internal object SubscriptionOpenRefresh {
    const val JOB=2402
    private const val CLOCK="SubscriptionRefreshClock"
    internal fun due(minutes:Int,last:Long,now:Long)=minutes>0&&(last<=0||now<last||now-last>=minutes*60_000L)
    fun idle(context:Context)=!McrfVpnService.cleanupPending()&&!McrfVpnService.ownsCurrentTun()&&!McrfVpnService.wifiPaused()&&
        !ConnectionPresentation.connected(McrfVpnService.status)&&!ConnectionPresentation.pending(McrfVpnService.status)&&
        !context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getBoolean("wantedRunning",false)
    fun completed(context:Context){context.getSharedPreferences(CLOCK,Context.MODE_PRIVATE).edit().putLong("completed",System.currentTimeMillis()).apply()}
    fun dueNow(context:Context)=due(SubscriptionOptionsStore.load(context).intervalMinutes,
        context.getSharedPreferences(CLOCK,Context.MODE_PRIVATE).getLong("completed",0),System.currentTimeMillis())
    fun onOpen(context:Context,hasSubscriptions:Boolean) {
        if(!hasSubscriptions||!idle(context))return
        val minutes=SubscriptionOptionsStore.load(context).intervalMinutes
        val last=context.getSharedPreferences(CLOCK,Context.MODE_PRIVATE).getLong("completed",0)
        if(!due(minutes,last,System.currentTimeMillis()))return
        val scheduler=context.getSystemService(JobScheduler::class.java)
        if(scheduler.getPendingJob(JOB)!=null)return
        val info=JobInfo.Builder(JOB,ComponentName(context,SubscriptionUpdateService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000)
            .setExtras(PersistableBundle().apply {putBoolean("onOpen",true)}).build()
        scheduler.schedule(info)
    }
}
