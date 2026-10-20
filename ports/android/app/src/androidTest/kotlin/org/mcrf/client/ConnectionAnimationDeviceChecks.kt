package org.mcrf.client

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Presentation only, and ONLY in a fresh disposable UID. Never start a VPN or modify human data. */
object ConnectionAnimationDeviceChecks {
    @JvmStatic fun run(test:Instrumentation):String {
        val context=test.targetContext
        check(context.packageName=="org.mcrf.client.animationfixture10"){"Fresh disposable animation package required"}
        val prefs=context.getSharedPreferences("MainActivity",0)
        check(prefs.all.isEmpty()){ "Animation fixture may not reset or reuse existing preferences" }
        check(prefs.edit().putBoolean(EngineUpdates.AUTOMATIC_PREFERENCE,false).commit())
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val report=StringBuilder();var checks=0
        fun onMain(block:()->Unit){val failure=AtomicReference<Throwable?>();test.runOnMainSync {try{block()}catch(e:Throwable){failure.set(e)}};failure.get()?.let {throw it}}
        fun pass(value:Boolean,label:String){check(value){label};checks++;report.append("PASS ").append(label).append('\n')}
        fun settle(){test.waitForIdleSync();val end=SystemClock.elapsedRealtime()+10000;while(activity.working()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(25);check(!activity.working());test.waitForIdleSync()}
        val out=File(context.externalCacheDir,"connection-animations").apply {check(mkdir()||isDirectory)}
        try {
            settle()
            onMain {
                val root=activity.window.decorView
                pass(root.findViewWithTag<View>("connection-primary") is ForgeConnectionButton,"default is Custom1")
                pass(root.findViewWithTag<View>("home-metrics").visibility==View.GONE,"Custom1 hides speed/session block")
                activity.navigate(ClientSettingsNavigation.APPEARANCE)
                val chooser=root.findViewWithTag<Spinner>("connection-window-style")
                val exit=root.findViewWithTag<Spinner>("connection-exit-style")
                pass(chooser.count==8&&exit.count==4,"all connect and independent disconnect choices")
                val before=prefs.all.toMap()
                root.findViewWithTag<View>("random-appearance").performClick()
                pass(prefs.all==before,"randomizer changes draft only")
                pass(root.findViewWithTag<TextView>("random-appearance-summary").text.isNotEmpty(),"randomizer summary includes connection choice")
                chooser.setSelection(ConnectionAppearance.choices.keys.indexOf(ConnectionAppearance.BLUEPRINT))
                exit.setSelection(ConnectionAppearance.exits.keys.indexOf(ConnectionAppearance.DOOR))
                root.findViewWithTag<Switch>("connection-pages-merged").isChecked=true
                root.findViewWithTag<View>("theme-colors-save").performClick()
            }
            settle()
            onMain {
                val root=activity.window.decorView
                pass(prefs.getString(ConnectionAppearance.STYLE,null)==ConnectionAppearance.BLUEPRINT&&prefs.getString(ConnectionAppearance.EXIT,null)==ConnectionAppearance.DOOR,"Apply commits styles together")
                activity.navigate("Главная")
                pass(root.findViewWithTag<View>("nav-Подключения")!=null&&root.findViewWithTag<View>("nav-Белые списки")==null,"single diamond navigation tab")
                pass(root.findViewWithTag<View>("connections-header-sections")!=null,"header VPN/BS links")
                val service=McrfVpnService.status;val selected=activity.selected
                pass(root.findViewWithTag<View>("connection-section-title")==null,"no invented upper connection title")
                root.findViewWithTag<View>("nav-Подключения").performClick()
                pass(root.findViewWithTag<View>("screen-Белые списки")!=null,"bottom tab switches visible pool")
                pass(McrfVpnService.status==service&&activity.selected==selected,"navigation never reconnects or changes selected server")
                root.findViewWithTag<View>("nav-Подключения").performClick()
                pass(root.findViewWithTag<View>("screen-Главная")!=null,"bottom tab returns to VPN")
                val header=root.findViewWithTag<TextView>("connections-header-sections")
                val spans=(header.text as android.text.Spanned).getSpans(0,header.text.length,android.text.style.ClickableSpan::class.java)
                pass(spans.size==2,"header exposes independent accessible text links")
                spans[1].onClick(header)
                pass(root.findViewWithTag<View>("screen-Белые списки")!=null&&McrfVpnService.status==service,"BS header link only changes visible list")
                val vpnHeader=root.findViewWithTag<TextView>("connections-header-sections")
                val vpnSpans=(vpnHeader.text as android.text.Spanned).getSpans(0,vpnHeader.text.length,android.text.style.ClickableSpan::class.java)
                vpnSpans[0].onClick(vpnHeader)
                pass(root.findViewWithTag<View>("screen-Главная")!=null&&activity.selected==selected,"VPN header link preserves selection")
                prefs.edit().putString(ConnectionAppearance.STYLE,ConnectionAppearance.CLASSIC).commit();activity.render()
                pass(root.findViewWithTag<View>("connection-primary") is RoundConnectionButton&&root.findViewWithTag<View>("home-metrics").visibility==View.GONE,"Classic control hides speed/session metrics")
                pass(root.findViewWithTag<android.widget.LinearLayout>("auto-bs-control").orientation==android.widget.LinearLayout.HORIZONTAL,"AutoDetect label and picker share one row in VPN")
                activity.navigate(ClientSettingsNavigation.WHITELISTS)
                pass(root.findViewWithTag<android.widget.LinearLayout>("auto-bs-control").orientation==android.widget.LinearLayout.HORIZONTAL,"AutoDetect label and picker share one row in BS")
                pass(root.findViewWithTag<android.widget.LinearLayout>("home-captcha-settings").orientation==android.widget.LinearLayout.HORIZONTAL,"CAPTCHA label and picker share one row")
                pass(root.findViewWithTag<View>("whitelist-engines")==null&&root.findViewWithTag<View>("whitelist-settings-${ClientSettingsNavigation.CAPTCHA}")==null,"BS quick buttons exclude engines and CAPTCHA")
                pass(root.findViewWithTag<TextView>("whitelist-settings-${ClientSettingsNavigation.VK}").text.toString()=="VK Turn","VK Turn quick action label")
                activity.navigate(ClientSettingsNavigation.DNS)
                pass(root.findViewWithTag<View>("dns-setting.enabled")==null,"DNS opt-in checkbox removed")
                pass(root.findViewWithTag<android.widget.LinearLayout>("dns-default-row").orientation==android.widget.LinearLayout.HORIZONTAL,"default DNS label and choice share one row")
                activity.navigate(ClientSettingsNavigation.ROOT)
                pass(root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.WHITELISTS}")==null,"settings do not duplicate main BS tab")
                pass(root.findViewWithTag<View>("settings-page-${ClientSettingsNavigation.CAPTCHA}")==null,"CAPTCHA remains per-server, not a settings section")
                activity.navigate("Главная")
                prefs.edit().putString(ConnectionAppearance.STYLE,ConnectionAppearance.BLUEPRINT).commit();activity.render()
                val model=ConnectionMotion();val view=ForgeConnectionButton(activity,model,ConnectionAppearance.BLUEPRINT,ConnectionAppearance.DOOR){}
                view.state(true,false,true,true,false,false,TrafficState.Snapshot(0,0,4096,8192,"fixture",true))
                pass(view.contentDescription.contains("↑")&&view.contentDescription.contains("↓"),"speed stays accessible when metrics hidden")
                view.state(false,false,true,false,true,false,TrafficState.Snapshot(0,0,0,0,"",false))
                pass(view.contentDescription.contains("Ошибка подключения"),"native action has failure semantics")
                view.scene.dispose()
                val baseline=prefs.all.toMap();val stores=ProfileStore.snapshot(context)
                ClientSettingsRegistry.register();AndroidBackup.validatePreferences(org.json.JSONObject().put(ConnectionAppearance.STYLE,ConnectionAppearance.BLUEPRINT).put(ConnectionAppearance.EXIT,ConnectionAppearance.DOOR).put(ConnectionAppearance.MERGED,true))
                pass(prefs.all==baseline&&ProfileStore.snapshot(context)==stores,"appearance validation preserves stores")
            }
            // Execute the PACKAGED renderer inside the phone's actual WebView. No alternate drawing.
            lateinit var scene:ConnectionScene
            onMain {
                scene=ConnectionScene(activity)
                (activity.window.decorView as android.view.ViewGroup).addView(scene,android.view.ViewGroup.LayoutParams(900,650))
            }
            try {
                val deadline=SystemClock.elapsedRealtime()+15000
                var loaded=false
                while(!loaded&&SystemClock.elapsedRealtime()<deadline){onMain {loaded=scene.isRendererLoaded};SystemClock.sleep(30)}
                pass(loaded,"packaged local WebView loaded")
                fun js(expression:String):String {
                    val done=java.util.concurrent.CountDownLatch(1);val answer=AtomicReference<String?>()
                    onMain {scene.fixture("JSON.stringify(window.McrfScene.$expression)"){answer.set(it);done.countDown()}}
                    check(done.await(12,java.util.concurrent.TimeUnit.SECONDS)){"Renderer callback timed out"}
                    return org.json.JSONTokener(answer.get()).nextValue() as String
                }
                fun update(style:String,exit:String="door",signal:String="CONNECTED",visible:Boolean=true,motion:Boolean=true,speed:Double=8.0){
                    val p=org.json.JSONObject().put("style",style).put("exit",exit).put("signal",signal).put("visible",visible).put("motion",motion).put("speed",speed)
                    val r=org.json.JSONObject(js("update($p)"))
                    pass(r.optString("error").isEmpty(),"controller $style/$signal without JS errors")
                }
                fun frame(mode:String,age:Double,motion:Boolean=true):org.json.JSONObject {
                    val r=org.json.JSONObject(js("testFrame('$mode',$age,$motion)"))
                    pass(r.getString("error").isEmpty()&&r.getInt("ink")>300,"actual Canvas pixels $mode/$age")
                    return r
                }
                fun capture(name:String){
                    val data=org.json.JSONTokener(js("capture()")).nextValue() as String;check(data.startsWith("data:image/png;base64,"))
                    File(out,"$name.png").writeBytes(android.util.Base64.decode(data.substringAfter(','),android.util.Base64.DEFAULT))
                }
                val states=listOf("idle" to 0.0,"forge" to 4.1,"break" to .78,"ready" to 18.7,"failure" to .7,"errorfall" to 1.3,"fall" to 1.5,"error" to 6.0)
                for(dark in listOf(false,true)){
                    onMain {Loft.apply(dark)}
                    for(key in ConnectionAppearance.choices.keys.filter {it!=ConnectionAppearance.CLASSIC}){
                        val style=ConnectionAppearance.canvasStyle(key)
                        onMain {scene.present(key,ConnectionAppearance.DOOR,ConnectionMotion.Signal.IDLE,8.0,true,true)}
                        update(style,signal="CONNECTING")
                        for((mode,age)in states){frame(mode,age);if(dark&&mode in listOf("ready","forge","fall"))capture("$style-$mode")}
                        // Blueprint flow is decorative, fixed at44; traffic must not change its pixels.
                        update(style,speed=0.0);val zero=frame("ready",18.7).getLong("hash")
                        update(style,speed=128.0);val high=frame("ready",18.7).getLong("hash")
                        if(style=="blueprint")pass(zero==high,"Blueprint stream is fixed independently of zero/high traffic")
                        frame("ready",18.7,false)
                    }
                }
                for(exit in listOf("door","orbital","lock","cryo")){
                    update("blueprint",exit);frame("fall",1.7);capture("exit-$exit")
                }
                update("plasma",signal="CONNECTING");update("plasma",signal="CONNECTED")
                val success=org.json.JSONObject(js("inspect()"))
                pass(success.getString("mode")=="break","actual signal enters success once")
                update("plasma",signal="CONNECTED")
                pass(org.json.JSONObject(js("inspect()")).getString("mode")=="break","duplicate connected observation never replays success")
                update("plasma",visible=false)
                pass(!org.json.JSONObject(js("inspect()")).getBoolean("looping"),"hidden scene cancels its RAF")
                update("plasma",motion=false)
                pass(!org.json.JSONObject(js("inspect()")).getBoolean("looping"),"reduced motion does not schedule RAF")
                pass(!McrfVpnService.cleanupPending()&&!ConnectionPresentation.connected(McrfVpnService.status),"fixture never started VPN")
            }finally{onMain {(scene.parent as? android.view.ViewGroup)?.removeView(scene);scene.dispose();Loft.apply(true)}}
            report.append("ARTIFACTS ").append(out.absolutePath).append('\n').append("CHECKS ").append(checks).append('\n')
            File(out,"report.txt").writeText(report.toString())
            return report.toString()
        }finally{onMain {activity.finish()}}
    }
}
