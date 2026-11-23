package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import java.util.concurrent.atomic.AtomicReference

/** Real settings/visibility/relaunch path; no connection or profile mutations. */
class EdgePanelVisibilityInstrumentation:Instrumentation() {
    private var allowPhone=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);allowPhone=arguments?.getString("allowPhone")=="true";start()}
    override fun onStart() {
        val report=Bundle();val prefs=targetContext.getSharedPreferences("MainActivity",0)
        val had=prefs.contains(EdgePanelAppearance.HIDDEN);val before=EdgePanelAppearance.hidden(prefs)
        var activity:MainActivity?=null
        fun main(action:()->Unit){val error=AtomicReference<Throwable?>();runOnMainSync {try{action()}catch(t:Throwable){error.set(t)}};error.get()?.let {throw it};waitForIdleSync()}
        fun dismissStartupPrompt() {
            main {
                fun later(view:View):View? {
                    if(view is android.widget.Button&&view.text.toString()=="Позже")return view
                    if(view is android.view.ViewGroup)repeat(view.childCount){later(view.getChildAt(it))?.let {found->return found}}
                    return null
                }
                android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::later)?.performClick()
            }
        }
        fun launch():MainActivity {
            val host=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=host
            val deadline=System.nanoTime()+15_000_000_000L
            while(host.working()&&System.nanoTime()<deadline)Thread.sleep(30)
            check(!host.working());return host
        }
        fun settings(host:MainActivity):Switch {
            lateinit var option:Switch
            main {
                host.navigate(ClientSettingsNavigation.APPEARANCE)
                if(host.window.decorView.findViewWithTag<View>("edge-appearance-settings")==null)
                    host.window.decorView.findViewWithTag<View>("appearance-edge-toggle").performClick()
                val panel=host.window.decorView.findViewWithTag<LinearLayout>("edge-appearance-settings")
                option=panel.getChildAt(0) as Switch
                check(option.tag==EdgePanelAppearance.HIDDEN&&option.text=="Скрыть боковой переключатель")
            }
            // Disclosure children must be measured before asking ScrollView to
            // reveal them; a zero-height creation-time rectangle cannot scroll.
            main {option.requestRectangleOnScreen(android.graphics.Rect(0,0,option.width,option.height),true)}
            return option
        }
        fun capture(name:String) {
            repeat(3){Thread.sleep(200);dismissStartupPrompt()};Thread.sleep(300)
            if(name=="settings-hidden")main {
                val option=requireNotNull(activity!!.window.decorView.findViewWithTag<View>(EdgePanelAppearance.HIDDEN))
                option.requestRectangleOnScreen(android.graphics.Rect(0,0,option.width,option.height),true)
                val visible=android.graphics.Rect();check(option.getGlobalVisibleRect(visible)&&visible.height()>=option.height)
            }
            val folder=java.io.File(targetContext.getExternalFilesDir(null),"edge-visibility-test");check(folder.isDirectory||folder.mkdirs())
            val bitmap=requireNotNull(uiAutomation.takeScreenshot())
            java.io.File(folder,"$name.png").outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        }
        try {
            check(BuildConfig.DEBUG&&(android.os.Build.MODEL.contains("sdk",true)||allowPhone)&&!McrfVpnService.hasActiveSession())
            var host=launch();val option=settings(host)
            main {option.isChecked=true;check(EdgePanelAppearance.hidden(prefs));check(host.window.decorView.findViewWithTag<View>("connection-edge").visibility==View.GONE)}
            capture("settings-hidden")
            main {host.navigate("Главная");check(host.window.decorView.findViewWithTag<View>("connection-edge").visibility==View.GONE)}
            capture("home-hidden")
            main {host.finish()};host=launch()
            main {check(EdgePanelAppearance.hidden(prefs));check(host.window.decorView.findViewWithTag<View>("connection-edge").visibility==View.GONE)}
            val restore=settings(host)
            main {check(restore.isChecked);restore.isChecked=false;check(host.window.decorView.findViewWithTag<View>("connection-edge").visibility==View.VISIBLE)}
            main {host.navigate("Главная");check(host.window.decorView.findViewWithTag<View>("connection-edge").visibility==View.VISIBLE)}
            capture("home-restored")
            report.putString("result","PASS: hide is first settings item, immediate and persistent through Activity relaunch, normal updates/navigation keep it hidden, settings restore it; no traffic started")
            finish(Activity.RESULT_OK,report)
        }catch(t:Throwable){report.putString("failure",t.javaClass.name+": "+t.message);finish(Activity.RESULT_CANCELED,report)}
        finally {activity?.let {host->main {host.finish()}};prefs.edit().apply {if(had)putBoolean(EdgePanelAppearance.HIDDEN,before)else remove(EdgePanelAppearance.HIDDEN)}.commit()}
    }
}
