package org.mcrf.client

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.ScrollView
import java.io.File

/** Real UI frames of an isolated UID: never record the human catalogue or start any connection. */
object DocsTourDeviceChecks {
    @JvmStatic fun run(test:Instrumentation):String {
        val context=test.targetContext
        check(context.packageName=="org.mcrf.client.onboardingfixture") {"DOCS_TOUR_REQUIRES_ISOLATED_UID"}
        ClientPrimarySelection.select(context,"singbox")
        val demo=Connections(context);demo.load()
        demo.add("vless://00000000-0000-4000-8000-000000000001@192.0.2.10:443?security=tls&sni=example.com#Demo%20Europe")
        demo.add("trojan://demo-only@198.51.100.20:443?security=tls&sni=example.org#Demo%20Reserve")
        check(context.getSharedPreferences("MainActivity",0).edit().putString("selected",demo.entries().first().id).commit())
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val output=File(context.externalCacheDir,"docs-tour").apply {check(mkdir())}
        var frame=0
        val times=mutableListOf<Long>()
        fun capture(milliseconds:Long) {
            val end=SystemClock.elapsedRealtime()+milliseconds
            while(SystemClock.elapsedRealtime()<end) {
                val frameStart=SystemClock.elapsedRealtime()
                times+=frameStart
                var captured:Bitmap?=null
                test.runOnMainSync {
                    val root=activity.window.decorView
                    check(root.width>0&&root.height>0)
                    val width=360;val height=(root.height.toLong()*width/root.width).toInt()
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    val canvas=Canvas(bitmap);canvas.scale(width.toFloat()/root.width,width.toFloat()/root.width);root.draw(canvas)
                    captured=bitmap
                }
                val bitmap=requireNotNull(captured)
                try {File(output,"frame-%04d.png".format(frame++)).outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
                finally {bitmap.recycle()}
                SystemClock.sleep((50-(SystemClock.elapsedRealtime()-frameStart)).coerceAtLeast(1))
            }
        }
        fun page(name:String) {test.runOnMainSync{activity.navigate(name)};capture(1700)}
        fun scroll() {
            test.runOnMainSync {
                fun find(view:android.view.View):ScrollView? {
                    if(view is ScrollView)return view
                    if(view is ViewGroup)repeat(view.childCount){find(view.getChildAt(it))?.let{return it}}
                    return null
                }
                find(activity.window.decorView)?.let {view->
                    val end=(view.getChildAt(0).height-view.height).coerceIn(0,activity.dp(380))
                    android.animation.ValueAnimator.ofInt(0,end).apply {duration=1300;addUpdateListener {view.scrollTo(0,it.animatedValue as Int)};start()}
                }
            }
            capture(1700)
        }
        try {
            SystemClock.sleep(1600);test.waitForIdleSync()
            capture(1700)
            page("Маршрутизация");scroll()
            page("Автодеплой")
            page(ClientSettingsNavigation.ROOT);scroll()
            page(ClientSettingsNavigation.DNS);scroll()
            page(ClientSettingsNavigation.ADVANCED);scroll()
            page("Главная")
            check(frame>100)
            File(output,"timings.json").writeText(org.json.JSONArray(times).toString())
            return "PASS real native fades and smooth scrolling captured in isolated demo UID\nPASS no VPN, account, deployment or human profile action\nDOCS_TOUR_FRAMES=$frame\n"
        }finally{test.runOnMainSync{activity.finish()}}
    }
}
