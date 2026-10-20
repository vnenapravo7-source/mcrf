package org.mcrf.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import java.util.concurrent.Executors

/** User-enabled resume only; no boot loop, permission bypass or persistent wake lock. */
class ClientBehaviorReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        val pending=goAsync();val worker=Executors.newSingleThreadExecutor()
        worker.execute {try{
            val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
            val value=ClientBehaviorStore.load(context)
            if(value.shouldReconnect(prefs.getBoolean("wantedRunning",false),ProfileStore(context).read().isNotBlank(),VpnService.prepare(context)==null,false)&&!EngineUpdates.installationPending(context)){
                context.startForegroundService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.START)
                    .putExtra("tun",prefs.getBoolean("tun",true)).putExtra("port",prefs.getInt("port",10808)).putExtra("applyRoutes",prefs.getBoolean("applyRoutes",true)))
            }
        }catch(_:Exception){ClientEvents.record(context,"Предупреждение","Автовосстановление не выполнено. Откройте MCRF для подключения.")}
        finally{pending.finish();worker.shutdown()}}
    }
}
