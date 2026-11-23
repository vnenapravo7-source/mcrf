package org.mcrf.client
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Owned emulator only: unbound/linked presentation, no invite redemption or network calls. */
class RequestInviteLayoutInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun main(action:()->Unit){val e=AtomicReference<Throwable?>();runOnMainSync{try{action()}catch(t:Throwable){e.set(t)}};e.get()?.let {throw it}}
    private fun find(v:View,p:(View)->Boolean):View?{if(p(v))return v;if(v is ViewGroup)repeat(v.childCount){find(v.getChildAt(it),p)?.let{return it}};return null}
    private fun window(p:(View)->Boolean)=android.view.inspector.WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull {find(it,p)}
    private fun capture(name:String){waitForIdleSync();Thread.sleep(200);val image=requireNotNull(uiAutomation.takeScreenshot());val dir=File(targetContext.getExternalFilesDir(null),"request-invite-final-ui").apply {mkdirs()};File(dir,"$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
    override fun onStart(){val report=Bundle();var host:MainActivity?=null
        val prefs=targetContext.getSharedPreferences("MainActivity",0);val old=prefs.getString(ClientPrimarySelection.KEY,null)
        try {
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            prefs.edit().putString(ClientPrimarySelection.KEY,"singbox").commit()
            host=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity=host!!;val end=System.nanoTime()+15_000_000_000L
            while(activity.working()&&System.nanoTime()<end)Thread.sleep(50);check(!activity.working())
            for(linked in listOf(false,true)) {
                var dialog:android.app.AlertDialog?=null
                main {
                    activity.navigate(ClientSettingsNavigation.ROOT)
                    val state=if(linked)InviteCodeSync.State("owned-layout-fixture",1,1,2,true)else null
                    val body=InviteCodeUi(activity){}.view(true,state)
                    dialog=ThemedDialogBuilder(activity,compactActions=true).setView(CompactUi.scroll(activity,body,.74f)).setNegativeButton("Закрыть",null).show()
                };waitForIdleSync()
                main {
                    val body=window{it.tag=="invite-code-settings"}!!
                    check(find(body){it is TextView&&(it.text.contains("Получить инвайт")||it.text.contains("Сохранить интервал"))}==null)
                    check(find(body){it.tag=="invite-refresh-interval"}==null)
                    val title=window{it.tag=="invite-dialog-title"}!!;val binding=window{it.tag=="invite-binding-title"}!!
                    val a=IntArray(2).also{title.getLocationOnScreen(it)};val b=IntArray(2).also{binding.getLocationOnScreen(it)};check(a[0]==b[0])
                    if(linked){
                        check(find(body){it.tag=="invite-bind"}==null)
                        for(tag in listOf("invite-refresh","invite-unlink")){val button=find(body){it.tag==tag} as Button;check(button.isShown&&button.height==activity.dp(48))}
                    }else {check(find(body){it.tag=="invite-bind"}!!.isShown);check(find(body){it.tag=="invite-refresh"||it.tag=="invite-unlink"}==null)}
                };capture(if(linked)"02-linked-owned-fixture"else"01-unbound")
                main{dialog!!.dismiss()};waitForIdleSync()
            }
            main{AutoExclusionsUi.show(activity)};waitForIdleSync()
            main{(window{it.tag=="auto-exclusions-countries"}!!).performClick()};waitForIdleSync()
            main {
                val anchor=window{it.tag=="auto-exclusions-countries"}!!;val popup=window{it.tag=="auto-exclusions-countries-popup"}!!
                val footer=anchor.rootView.findViewById<View>(android.R.id.button1)
                val a=IntArray(2).also{anchor.getLocationOnScreen(it)};val b=IntArray(2).also{popup.getLocationOnScreen(it)};val c=IntArray(2).also{footer.getLocationOnScreen(it)}
                check(kotlin.math.abs(b[1]-a[1]-anchor.height)<=activity.dp(1)){"anchor=${a[1]} h=${anchor.height} popup=${b[1]}"};check(b[1]+popup.height<=c[1]-activity.dp(8)){"popupBottom=${b[1]+popup.height} footer=${c[1]}"};check(popup.width==anchor.width)
            };capture("03-exclusions-no-overlap")
            main{window{it.tag=="auto-exclusions-countries-done"}!!.performClick()};waitForIdleSync()
            main{(window{it is Button&&it.text.toString()=="Закрыть"}!!).performClick()};waitForIdleSync()
            report.putString("result","PASS: compact unbound Bind / owned linked Refresh-Unlink; no Get Invite or interval controls; aligned headings; country field anchoring and >=8dp clearance above dialog actions; 3 screenshots; no network")
            finish(Activity.RESULT_OK,report)
        }catch(t:Throwable){report.putString("failure",t.stackTraceToString());finish(Activity.RESULT_CANCELED,report)}finally {
            main{host?.finish()};prefs.edit().apply{if(old==null)remove(ClientPrimarySelection.KEY)else putString(ClientPrimarySelection.KEY,old)}.commit()
        }
    }
}
