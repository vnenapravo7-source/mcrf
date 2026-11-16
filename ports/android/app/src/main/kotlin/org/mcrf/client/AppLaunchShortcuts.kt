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
import java.util.UUID

internal object AppLaunchShortcuts {
    const val PIN_ACTION="org.mcrf.client.LAUNCH_GROUP_SHORTCUT"
    private const val HANDOFF_ACTION="org.mcrf.client.LAUNCH_GROUP_HANDOFF"
    private const val REQUEST="request"
    private data class Request(val key:String,val id:String,val createdAt:Long)
    private var pending:Request?=null

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

    fun requestLaunch(context:Context,id:String) {
        val request=Request(UUID.randomUUID().toString(),id,SystemClock.elapsedRealtime())
        pending=request
        context.startActivity(Intent(context,MainActivity::class.java).setAction(HANDOFF_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(REQUEST,request.key))
    }

    fun handle(activity:MainActivity,intent:Intent,loading:Boolean):Boolean {
        if(intent.action!=HANDOFF_ACTION)return false
        val request=pending?:return true
        if(request.key!=intent.getStringExtra(REQUEST))return true
        if(loading)return true
        pending=null
        if(SystemClock.elapsedRealtime()-request.createdAt>30000)return true
        if(!activity.canEditInterface())return true
        val mode=AppLaunchGroupsUi.modeFor(activity,request.id)
        if(mode==null)activity.alert("Ярлык приложения","Приложение больше не входит в список запуска MCRF.")
        else activity.launchFromGroup(request.id,mode.withVpn)
        return true
    }
}

/** Android starts the pinned shortcut with the publisher's own permissions. */
class AppLaunchShortcutActivity:Activity() {
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        try {if(state==null)AppLaunchShortcuts.forward(this,intent)} finally {finish()}
    }
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent)
        try {AppLaunchShortcuts.forward(this,intent)} finally {finish()}
    }
}
