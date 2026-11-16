package org.mcrf.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import java.util.concurrent.Executors

/** User-enabled resume only; no boot loop, permission bypass or persistent wake lock. */
class ClientBehaviorReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val resume=intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)
        if(!resume&&intent.action !in setOf(Intent.ACTION_USER_UNLOCKED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED))return
        CsqttMigrationRuntime.request(context.applicationContext)
        val pending=goAsync();val worker=Executors.newSingleThreadExecutor()
        worker.execute {try{
            // Persist an immediate reevaluation. Existing reconnect consent below remains independent.
            runCatching {BehaviorScheduleRecovery.recover(context)}
            if(!resume)return@execute
            val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
            val value=ClientBehaviorStore.load(context)
            if(!SafeModeStore(context).enabled()&&!prefs.getBoolean(McrfVpnService.RECOVERY_USER_STOPPED,false)&&
                value.shouldReconnect(prefs.getBoolean("wantedRunning",false),ProfileStore(context).read().isNotBlank(),VpnService.prepare(context)==null,McrfVpnService.hasActiveSession())&&!EngineUpdates.installationPending(context)){
                context.startForegroundService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.START)
                    .putExtra(McrfVpnService.RESUME_REQUEST,true)
                    .putExtra("tun",prefs.getBoolean("tun",true)).putExtra("port",prefs.getInt("port",10808)).putExtra("applyRoutes",prefs.getBoolean("applyRoutes",true)))
            }
        }catch(_:Exception){ClientEvents.record(context,"Предупреждение","Автовосстановление не выполнено. Откройте MCRF для подключения.")}
        finally{pending.finish();worker.shutdown()}}
    }
}
