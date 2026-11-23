package org.mcrf.client

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ToggleButton

/** Owned-device regression. Caller supplies overlay permission; no VPN or profile changes. */
class ResourceMonitorDualInstrumentation:Instrumentation() {
    private fun main(action:()->Unit){var error:Throwable?=null;runOnMainSync {try{action()}catch(t:Throwable){error=t}};error?.let {throw it}}
    private fun hud(view:View):Boolean=view is ResourceMonitorHud || (view is ViewGroup && (0 until view.childCount).any {hud(view.getChildAt(it))})
    private fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){var ready=false;main {ready=test()};if(ready)return;Thread.sleep(100)};error("Monitor state timed out")}
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=Bundle();var activity:MainActivity?=null
        val prefs=targetContext.getSharedPreferences("MainActivity",0)
        val existed=prefs.contains("resourceMonitorQuickStart");val saved=prefs.getBoolean("resourceMonitorQuickStart",false)
        try {
            check(Settings.canDrawOverlays(targetContext));check(!McrfVpnService.hasActiveSession())
            check(!ResourceMonitorOverlayService.running && !ResourceMonitorOverlayService.starting)
            prefs.edit().putBoolean("resourceMonitorQuickStart",false).commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host=activity!!;val monitor=host.resourceMonitor
            fun toggle(id:String){
                val field=ResourceMonitorController::class.java.getDeclaredField("settingsPopup").apply {isAccessible=true}
                val dialog=field.get(monitor) as AlertDialog
                (dialog.window!!.decorView.findViewWithTag<View>(id) as ToggleButton).performClick()
            }
            main {monitor.showSettings();toggle("resource-monitor-in-app");check(monitor.enabled);check(hud(host.window.decorView));toggle("resource-monitor-system")}
            waitFor {ResourceMonitorOverlayService.running && monitor.snapshot().processPssBytes!=null}
            main {
                check(monitor.enabled && hud(host.window.decorView))
                check(monitor.snapshot()==ResourceMonitorOverlayService.snapshot())
                val local=ResourceMonitorController::class.java.getDeclaredField("sampler").apply {isAccessible=true}.get(monitor)
                check(!ResourceMonitorSampler::class.java.getDeclaredField("running").apply {isAccessible=true}.getBoolean(local)){"Duplicate local sampler"}
                monitor.startRecording();check(ResourceMonitorOverlayService.snapshot().recording.active)
                monitor.resetRecording();check(ResourceMonitorOverlayService.snapshot().recording.active)
                monitor.onPause();check(ResourceMonitorOverlayService.running);check(!hud(host.window.decorView))
                check(ResourceMonitorOverlayService.snapshot().recording.active)
                monitor.onResume();check(monitor.enabled && hud(host.window.decorView))
                monitor.showSettings();toggle("resource-monitor-in-app")
                check(!monitor.enabled && !hud(host.window.decorView));check(ResourceMonitorOverlayService.running)
                toggle("resource-monitor-in-app");check(monitor.enabled && hud(host.window.decorView))
                toggle("resource-monitor-system");check(!ResourceMonitorOverlayService.running);check(monitor.enabled && hud(host.window.decorView))
            }
            waitFor {monitor.snapshot().processPssBytes!=null}
            main {monitor.stop();check(!monitor.enabled && !ResourceMonitorOverlayService.running && !hud(host.window.decorView))}
            report.putString("result","PASS: both toggle orders, shared sampler, independent stop, background/resume, recording/reset, global stop")
            finish(Activity.RESULT_OK,report)
        } catch(t:Throwable){report.putString("failure",t.stackTraceToString());finish(Activity.RESULT_CANCELED,report)}
        finally {
            main {ResourceMonitorOverlayService.stop(targetContext);activity?.let {it.resourceMonitor.close();it.finish()}}
            prefs.edit().apply {if(existed)putBoolean("resourceMonitorQuickStart",saved)else remove("resourceMonitorQuickStart")}.commit()
        }
    }
}
