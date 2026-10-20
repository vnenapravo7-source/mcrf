package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Spinner
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

object PreferenceDeviceChecks {
    @JvmStatic fun run(instrumentation: android.app.Instrumentation, temporary: File, passed: Consumer<String>) {
        val target = instrumentation.targetContext
        val before = ProfileStore.snapshot(target)
        val preferences = target.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).all.toMap()
        val isolated = object : ContextWrapper(target) { override fun getFilesDir() = temporary }
        val routes = RoutingStore(isolated).apply { load(); save(snapshot()) }
        val activity = instrumentation.startActivitySync(Intent(target,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val end = System.nanoTime()+10_000_000_000L
        while(activity.working() && System.nanoTime()<end) Thread.sleep(50)
        check(!activity.working())
        try {
            fun layout(view: android.view.View) {
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(activity.dp(320),android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,view.measuredWidth,view.measuredHeight)
            }
            var calls = 0
            instrumentation.runOnMainSync {
                val off = AutoSelectSwitch(activity,true,{true}) { value -> check(!value); calls++ }
                off.isChecked = false
                check(!off.isChecked && calls == 1)
                off.isChecked = true
                check(!off.isChecked && calls == 1)
            }
            passed.accept("Busy automatic switch turns off immediately and never reenables recursively")
            lateinit var editor: DnsEditor
            val saves = AtomicInteger()
            instrumentation.runOnMainSync {
                editor = DnsEditor(activity,routes.snapshot().getJSONObject("globalDns"))
                editor.onChange { try { val value=editor.value(); if(value.toString()!=routes.snapshot().getJSONObject("globalDns").toString()){routes.updateDns(value);saves.incrementAndGet()} } catch(_:IllegalArgumentException){} }
                activity.setContentView(editor.view)
                layout(editor.view)
            }
            instrumentation.waitForIdleSync()
            fun <T> descendants(view: android.view.View, type:Class<T>):List<T> {
                val found=mutableListOf<T>();if(type.isInstance(view))found.add(type.cast(view)!!)
                if(view is android.view.ViewGroup)repeat(view.childCount){found.addAll(descendants(view.getChildAt(it),type))};return found
            }
            instrumentation.runOnMainSync { descendants(editor.view,Spinner::class.java)[0].setSelection(DnsConfig.PROVIDERS.indexOf("Google")); layout(editor.view) }
            instrumentation.waitForIdleSync(); Thread.sleep(500)
            check(routes.snapshot().getJSONObject("globalDns").getString("provider")=="Google" && saves.get()>0) {
                "DNS provider not persisted: stored=${routes.snapshot().getJSONObject("globalDns").getString("provider")}, current=${editor.value().getString("provider")}, callbacks=${saves.get()}"
            }
            passed.accept("DNS provider change persists without a Save button")
            instrumentation.runOnMainSync { descendants(editor.view,Spinner::class.java)[1].setSelection(DnsConfig.protocols("Google").indexOf("DoT")); layout(editor.view) }
            instrumentation.waitForIdleSync(); Thread.sleep(150)
            check(RoutingStore(isolated).apply { load() }.snapshot().getJSONObject("globalDns").getString("protocol")=="DoT")
            passed.accept("DNS protocol survives encrypted store reload and keeps other routing fields")
            val routeBefore=routes.snapshot();val settings=JSONObject().put("provider","Свой").put("protocol","DoH").put("custom","bad://incomplete")
            try { routes.updateDns(settings);error("Invalid DNS was saved") }catch(_:IllegalArgumentException){}
            check(routes.snapshot().toString()==routeBefore.toString())
            passed.accept("Invalid custom DNS leaves the last valid routes unchanged")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
        check(ProfileStore.snapshot(target)==before && target.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences)
        passed.accept("Preference UI tests preserve every real user profile and preference")
    }
}
