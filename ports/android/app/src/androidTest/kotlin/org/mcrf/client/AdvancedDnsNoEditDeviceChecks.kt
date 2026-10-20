package org.mcrf.client

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.LinearLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Coordinator-only no-edit acceptance fixture, isolated encrypted store; never opens real Settings. */
object AdvancedDnsNoEditDeviceChecks {
    @JvmStatic fun run(instrumentation:Instrumentation,temporary:File,passed:Consumer<String>) {
        val target=instrumentation.targetContext;val real=ProfileStore.snapshot(target)
        val preferences=target.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
        val scoped=object:ContextWrapper(target){override fun getFilesDir()=temporary}
        val store=RoutingStore(scoped).apply {load();val state=snapshot();state.put("advancedDns",AdvancedDnsConfig.defaults()).put("geoData",GeoDataConfig.defaults());state.getJSONObject("advancedDns").getJSONObject("direct").put("providerMetadata",org.json.JSONObject().put("retain",true));save(state)}
        val disk=File(temporary,"routing.enc");check(disk.isFile)
        val activity=instrumentation.startActivitySync(Intent(target,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun onMain(block:()->Unit){val failure=AtomicReference<Throwable?>();instrumentation.runOnMainSync {try{block()}catch(e:Throwable){failure.set(e)}};failure.get()?.let {throw it}}
        fun drain(){instrumentation.waitForIdleSync();Thread.sleep(650);val done=CountDownLatch(1);RoutingDefaultsWrites.submit {done.countDown()};check(done.await(5,TimeUnit.SECONDS));instrumentation.waitForIdleSync()}
        try {
            val until=System.nanoTime()+10_000_000_000L;while(activity.working()&&System.nanoTime()<until)Thread.sleep(50);check(!activity.working())
            for(mode in listOf("existing metadata","absent extensions")){
            if(mode=="absent extensions"){val state=store.snapshot();state.remove("advancedDns");state.remove("geoData");store.save(state)}
            val baseline=disk.readBytes();val logical=store.snapshot()
            for(kind in listOf("GeoData","AdvancedDns")){
                onMain {activity.setContentView(if(kind=="GeoData")GeoDataEditor(activity,store).buildPage()else AdvancedDnsEditor(activity,store).buildPage())}
                drain();onMain {activity.setContentView(LinearLayout(activity))};drain()
                check(disk.readBytes().contentEquals(baseline)) {"$kind open/close rewrote isolated routing ciphertext without editing"}
                check(RoutingDefaultsEdits.equivalent(store.snapshot(),logical)) {"$kind open/close changed isolated DNS metadata/settings"}
                passed.accept("$kind ($mode) no-edit open/close preserves exact isolated ciphertext and all metadata")
            }
            }
        }finally{onMain {activity.finish()};instrumentation.waitForIdleSync();check(ProfileStore.snapshot(target)==real&&target.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences) {"No-edit fixture changed real user baseline"}}
        passed.accept("Synthetic form checks preserve real profiles/preferences; no saved settings, VPN or external network used")
    }
}
