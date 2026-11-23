package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup

/** Real cold-controller regression. No profiles, VPN, overlay permission or network changes. */
class ResourceMonitorStartupInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun main(action:()->Unit){var error:Throwable?=null;runOnMainSync {try{action()}catch(t:Throwable){error=t}};error?.let {throw it}}
    private fun containsHud(view:View):Boolean=view is ResourceMonitorHud||(view is ViewGroup&&(0 until view.childCount).any {containsHud(view.getChildAt(it))})
    override fun onStart(){
        val report=Bundle();val prefs=targetContext.getSharedPreferences("MainActivity",0)
        val existed=prefs.contains("resourceMonitorQuickStart");val hidden=prefs.getBoolean("resourceMonitorQuickStart",false)
        var activity:MainActivity?=null
        try {
            check(!McrfVpnService.hasActiveSession())
            prefs.edit().putBoolean("resourceMonitorQuickStart",true).commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host=activity!!
            main {check(!host.resourceMonitor.enabled);check(host.resourceMonitor.indicatorHidden);host.resourceMonitor.openShortcut();check(host.resourceMonitor.enabled);check(!containsHud(host.window.decorView))}
            val end=SystemClock.uptimeMillis()+15000;var sampled=false
            while(!sampled&&SystemClock.uptimeMillis()<end){main {sampled=host.resourceMonitor.snapshot().processPssBytes!=null};if(!sampled)Thread.sleep(100)}
            check(sampled){"Cold hidden monitor did not sample"}
            main {host.resourceMonitor.startRecording();check(host.resourceMonitor.snapshot().recording.active);host.resourceMonitor.stopRecording();check(!host.resourceMonitor.snapshot().recording.active);check(!containsHud(host.window.decorView))}
            report.putString("result","PASS: saved-hidden cold controller starts on first open, samples, records/pauses without toggling indicator modes")
            finish(Activity.RESULT_OK,report)
        }catch(t:Throwable){report.putString("failure",t.stackTraceToString());finish(Activity.RESULT_CANCELED,report)}
        finally {
            main {activity?.let {it.resourceMonitor.close();it.finish()}}
            prefs.edit().apply {if(existed)putBoolean("resourceMonitorQuickStart",hidden)else remove("resourceMonitorQuickStart")}.commit()
        }
    }
}
