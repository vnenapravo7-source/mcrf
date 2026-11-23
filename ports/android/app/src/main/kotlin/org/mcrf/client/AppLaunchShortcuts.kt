package org.mcrf.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.widget.Toast

internal object AppLaunchShortcuts {
    const val LAST_VPN_SERVER="anubisLastConnectedServer"
    const val PIN_ACTION="org.mcrf.client.LAUNCH_GROUP_SHORTCUT"
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var revision=0

    fun pin(activity:MainActivity,id:String,label:String) {
        val manager=activity.getSystemService(ShortcutManager::class.java)
        if(manager?.isRequestPinShortcutSupported!=true){
            activity.toast("Рабочий стол не поддерживает закрепление ярлыков.")
            return
        }
        val icon=runCatching {
            val source=activity.packageManager.getApplicationIcon(id)
            val size=minOf(manager.iconMaxWidth,manager.iconMaxHeight,128)
            val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            source.setBounds(0,0,size,size)
            source.draw(Canvas(bitmap))
            Icon.createWithBitmap(bitmap)
        }.getOrElse {Icon.createWithResource(activity,R.drawable.mcrf_logo)}
        val shortcutId="mcrf-app-$id"
        val destination=Uri.Builder().scheme("mcrf-app-shortcut").authority("launch").appendPath(id).build()
        val shortcut=ShortcutInfo.Builder(activity,shortcutId)
            .setShortLabel(label.ifBlank {id}.take(32))
            .setIcon(icon)
            .setIntent(Intent(activity,AppLaunchShortcutActivity::class.java).setAction(PIN_ACTION).setData(destination))
            .build()
        if(runCatching {manager.pinnedShortcuts.any {it.id==shortcutId}}.getOrDefault(false)){
            if(runCatching {manager.updateShortcuts(listOf(shortcut))}.getOrDefault(false))
                activity.toast("Ярлык уже закреплён.")
            else activity.toast("Не удалось обновить закреплённый ярлык.")
            return
        }
        if(!runCatching {manager.requestPinShortcut(shortcut,null)}.getOrDefault(false))
            activity.toast("Не удалось запросить закрепление ярлыка.")
    }

    fun forward(context:Context,intent:Intent) {
        if(intent.action!=PIN_ACTION)return
        val destination=intent.data?:return
        if(destination.scheme!="mcrf-app-shortcut"||destination.authority!="launch")return
        val id=destination.pathSegments.singleOrNull()?:return
        if(id==context.packageName)return
        requestLaunch(context,id)
    }

    fun requestLaunch(context:Context,id:String,alreadyOpen:Boolean=false) {
        val app=context.applicationContext
        if(!AnubisRouteAccess.allowed(app))return
        val mode=AppLaunchGroupsUi.modeFor(app,id)?:run {
            feedback(app,"Приложение больше не входит в список запуска MCRF.")
            return
        }
        val manager=app.packageManager
        if(manager.getLaunchIntentForPackage(id)==null&&
            !(mode.needsManagement&&ManagedAppFreezeUi.waitingForAutomaticRestore(app,id))){
            feedback(app,"Приложение недоступно.")
            return
        }
        if(mode.withVpn&&ManagedAppFreezeUi.selectedForFreeze(app,id)){
            feedback(app,"Приложение скрывается при подключении VPN.")
            return
        }
        val request=++revision
        val deadline=SystemClock.elapsedRealtime()+30_000L
        val prefs=app.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        var startedForApp=false
        var switching=false
        var stopRequested=false
        fun ownReady()=McrfVpnService.ownsSystemTunnel()&&ConnectionPresentation.connected(McrfVpnService.status)
        fun ownStarting()=prefs.getBoolean("tun",true)&&ConnectionPresentation.pending(McrfVpnService.status)
        val wait=object:Runnable {
            override fun run(){
                if(request!=revision||!AnubisRouteAccess.allowed(app)||AppLaunchGroupsUi.modeFor(app,id)!=mode)return
                if(SystemClock.elapsedRealtime()>=deadline){
                    feedback(app,if(mode.withVpn&&ownReady())"Приложение не восстановилось. Проверьте доступ Anubis."
                        else if(mode.withVpn)"VPN не подключился." else "VPN не отключился.")
                    return
                }
                if(mode.withVpn&&!ownReady()&&!switching&&!ownStarting()&&!McrfVpnService.cleanupPending()){
                    switching=true
                    McrfQuickTile.executeInOrder {
                        if(request!=revision)return@executeInOrder
                        val result=runCatching {
                            if(ownReady()||ownStarting())return@runCatching false
                            val entries=Connections(app).apply {load()}.entries()
                            val remembered=listOf(prefs.getString(LAST_VPN_SERVER,null),prefs.getString("selected",null),
                                BsAutoSelectionPolicy.last(prefs,false),BsAutoSelectionPolicy.last(prefs,true))
                            val server=remembered.firstNotNullOfOrNull {id->entries.firstOrNull {it.id==id}}
                                ?:error("Сначала выберите сервер в MCRF.")
                            if(request!=revision||AppLaunchGroupsUi.modeFor(app,id)!=mode)return@runCatching false
                            McrfQuickTile.startServer(app,server,true)
                            true
                        }
                        handler.post {
                            if(request!=revision)return@post
                            result.onSuccess {startedForApp=it;handler.post(this)}
                                .onFailure {feedback(app,it.message?:"VPN не подключился.")}
                        }
                    }
                    return
                }
                if(!mode.withVpn&&!stopRequested&&(McrfVpnService.ownsSystemTunnel()||ownStarting())){
                    stopRequested=true
                    runCatching {app.startService(Intent(app,McrfVpnService::class.java).setAction(McrfVpnService.STOP))}
                        .onFailure {feedback(app,"Не удалось остановить VPN.");return}
                }
                val ready=if(mode.withVpn)ownReady() else !McrfVpnService.ownsSystemTunnel()&&
                    !ownStarting()&&!McrfVpnService.cleanupPending()&&!systemVpn(app)
                if(ready&&!ManagedAppFreezeUi.isFrozen(app,id)){
                    val launch=manager.getLaunchIntentForPackage(id)
                    if(launch!=null){
                        runCatching {
                            if(startedForApp)AnubisLaunchObserver.followApp(app,id,McrfVpnService.selectedId)
                            if(!alreadyOpen)context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            ClientEvents.record(app,"Anubis",if(mode.withVpn)"Приложение открыто с VPN." else "Приложение открыто без VPN.")
                        }.onFailure {feedback(app,"Android не разрешил открыть приложение.")}
                        return
                    }
                }
                handler.postDelayed(this,150)
            }
        }
        ManagedAppFreezeUi.observe(app)
        handler.post(wait)
    }

    private fun systemVpn(context:Context):Boolean {
        val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
        return manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true
        }
    }

    private fun feedback(context:Context,message:String){
        ClientEvents.record(context,"Anubis",message)
        Toast.makeText(context,message,Toast.LENGTH_SHORT).show()
    }
}

/** Android starts the pinned shortcut with the publisher's own permissions. */
class AppLaunchShortcutActivity:Activity() {
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        try {if(state==null)forward(intent)} finally {finish()}
    }
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent)
        try {forward(intent)} finally {finish()}
    }
    private fun forward(intent:Intent) {
        if(intent.action?.startsWith(McrfHomeWidget.ACTION_PREFIX)!=true)AppLaunchShortcuts.forward(this,intent)
    }
}
