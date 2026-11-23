package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray

class AppLaunchShortcutsInstrumentation:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}

    override fun onStart(){
        val result=Bundle()
        val app=targetContext
        val target="moe.shizuku.privileged.api"
        val title="MCRF test shortcut"
        val prefs=app.getSharedPreferences("MainActivity",0)
        val keys=AppLaunchGroupsUi.Mode.entries.map {it.key}
        val previous=keys.associateWith {prefs.getString(it,null)}
        var stage="scope"
        var code=Activity.RESULT_CANCELED
        fun await(label:String,action:()->Boolean){
            val stop=SystemClock.elapsedRealtime()+15000
            while(!action()){
                check(SystemClock.elapsedRealtime()<stop){label}
                SystemClock.sleep(150)
            }
        }
        fun click(text:String):Boolean {
            val root=uiAutomation.rootInActiveWindow?:return false
            for(node in root.findAccessibilityNodeInfosByText(text)){
                if(!text.equals(node.text?.toString(),ignoreCase=true)&&!text.equals(node.contentDescription?.toString(),ignoreCase=true))continue
                var current:AccessibilityNodeInfo?=node
                while(current!=null){
                    if(current.isClickable&&current.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true
                    current=current.parent
                }
            }
            return false
        }
        fun home(){
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")).use {it.readBytes()}
        }
        fun select(mode:AppLaunchGroupsUi.Mode?){
            check(prefs.edit().apply {AppLaunchGroupsUi.Mode.entries.forEach {
                putString(it.key,if(it==mode)JSONArray(listOf(target)).toString()else"[]")
            }}.commit())
        }
        try {
            check(BuildConfig.DEBUG&&android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
            check(!app.packageManager.getActivityInfo(ComponentName(app,AppLaunchShortcutActivity::class.java),0).exported)
            check(app.packageManager.getLaunchIntentForPackage(target)!=null)
            check(!McrfVpnService.hasActiveSession())
            val host=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("test_no_prompts",true)) as MainActivity
            await("MCRF startup"){!host.working()&&host.hasWindowFocus()}
            select(AppLaunchGroupsUi.Mode.WITH_VPN)
            stage="launcher pin"
            runOnMainSync {AppLaunchShortcuts.pin(host,target,title)}
            val manager=app.getSystemService(ShortcutManager::class.java)
            await("Pin confirmation"){
                manager.pinnedShortcuts.any {it.id=="mcrf-app-$target"}||
                    listOf("Add automatically","Add to Home screen","Add").any(::click)
            }
            await("Launcher did not record pinned shortcut"){manager.pinnedShortcuts.any {it.id=="mcrf-app-$target"}}
            stage="changed group on cold launch"
            select(AppLaunchGroupsUi.Mode.WITHOUT_VPN)
            runOnMainSync {host.finish()}
            home()
            await("Pinned icon not found"){click(title)}
            await("Moved shortcut did not use the current group"){uiAutomation.rootInActiveWindow?.packageName==target}
            check(!McrfVpnService.ownsSystemTunnel())
            stage="removed group on warm launch"
            select(null)
            home()
            await("Pinned icon missing after use"){click(title)}
            await("Missing rejection for removed group"){
                ClientEvents.read(app).lineSequence().any {
                    it.endsWith("[Anubis] Приложение больше не входит в список запуска MCRF.")
                }
            }
            check(!McrfVpnService.ownsSystemTunnel())
            result.putString("verified","system pin, cold activity launch, changed group, stale shortcut rejection and private entry")
            code=Activity.RESULT_OK
        }catch(error:Throwable){
            result.putString("failure","$stage: ${error.javaClass.simpleName}: ${error.message}")
        }finally{
            prefs.edit().apply {for((key,value) in previous)if(value==null)remove(key)else putString(key,value)}.commit()
        }
        finish(code,result)
    }
}
