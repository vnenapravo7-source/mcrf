package org.mcrf.client

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper
import java.util.function.Consumer

/** Real dialog and encrypted fixture writes, but no native engines, network or user rule changes. */
object RoutingPresetUiDeviceChecks {
    @JvmStatic fun run(test:Instrumentation,temporary:File,passed:Consumer<String>){
        val target=test.targetContext;val before=ProfileStore.snapshot(target)
        val scoped=object:ContextWrapper(target){override fun getFilesDir()=temporary}
        val store=RoutingStore(scoped).apply {load();save(RouteRules.defaults())}
        val catalogue=PresetAssets.catalogue(target);val groups=RoutingDefaults.groups(catalogue)
        val activity=test.startActivitySync(Intent(target,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var dialog:AlertDialog?=null
        fun main(block:()->Unit){
            val error=AtomicReference<Throwable?>();val completed=CountDownLatch(1)
            check(Handler(Looper.getMainLooper()).post {try{block()}catch(e:Throwable){error.set(e)}finally{completed.countDown()}})
            check(completed.await(15,TimeUnit.SECONDS)){"Preset UI main-thread operation exceeded 15 seconds"}
            error.get()?.let {throw it}
        }
        // A continuously animated window need not become globally idle. Await a bounded
        // main-queue barrier instead; the assertions below still verify the actual views.
        fun idle(){Thread.sleep(100);main { }}
        fun waitReady(){val until=System.nanoTime()+15_000_000_000L;while(activity.working()&&System.nanoTime()<until)Thread.sleep(50);check(!activity.working());idle()}
        fun open(){
            android.util.Log.i("McrfPresetFixture","Opening picker")
            waitReady();val opened=AtomicReference<AlertDialog?>()
            main {RoutingDefaultsPresetPicker.show(activity,store){opened.set(it)}}
            val until=System.nanoTime()+10_000_000_000L;while(opened.get()==null&&System.nanoTime()<until)Thread.sleep(50)
            dialog=requireNotNull(opened.get());waitReady()
            android.util.Log.i("McrfPresetFixture","Picker ready")
        }
        fun root()=requireNotNull(dialog).window!!.decorView
        fun boxes(view:View):List<CheckBox> = buildList {
            if(view is CheckBox)add(view)
            if(view is ViewGroup)repeat(view.childCount){addAll(boxes(view.getChildAt(it)))}
        }
        try{
            open();val unchanged=store.snapshot().toString()
            main {
                val r=root();check(r.findViewWithTag<Spinner>("preset-type").count==2)
                check(boxes(r).size==groups.size)
                r.findViewWithTag<Spinner>("preset-type").setSelection(1)
            };idle()
            main {
                val r=root();check(boxes(r).size==53);check(r.findViewWithTag<View>("preset-row-geoip:cn")==null)
                check(r.findViewWithTag<View>("preset-row-geoip:ru")!=null)
                r.findViewWithTag<EditText>("preset-search").setText(groups.first().first)
            };idle()
            main {check(boxes(root()).any {it.tag.toString().startsWith("preset-row-service-")});root().findViewWithTag<Spinner>("preset-type").setSelection(0);root().findViewWithTag<EditText>("preset-search").setText("google")};idle()
            main {check(root().findViewWithTag<View>("preset-row-geosite:google")!=null);root().findViewWithTag<EditText>("preset-search").setText("");root().findViewWithTag<ScrollView>("preset-scroll").scrollTo(0,10000);root().findViewWithTag<Spinner>("preset-type").setSelection(1)};idle()
            main {check(root().findViewWithTag<ScrollView>("preset-scroll").scrollY==0);root().findViewWithTag<View>("preset-close").performClick()};idle()
            check(store.snapshot().toString()==unchanged)
            passed.accept("Merged Russia-only GeoIP/52 GeoSite, global service+geo search, dropdown scroll reset and close without writes")
            for(destination in listOf("direct","proxy","block")){
                open()
                main {root().findViewWithTag<CheckBox>("preset-row-service-0").performClick();root().findViewWithTag<EditText>("preset-search").setText("google")};idle()
                main {
                    root().findViewWithTag<CheckBox>("preset-row-geosite:google").performClick()
                    val r=root();val image=android.graphics.Bitmap.createBitmap(r.width,r.height,android.graphics.Bitmap.Config.ARGB_8888)
                    try{r.draw(android.graphics.Canvas(image));File(target.externalCacheDir,"mcrf-presets-$destination.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
                    r.findViewWithTag<View>("preset-apply-$destination").performClick()
                };waitReady()
                main {check(!requireNotNull(dialog).isShowing)}
                val rules=RoutingStore(scoped).apply {load()}.snapshot().getJSONArray("rules")
                check(rules.length()==2)
                repeat(rules.length()){check(rules.getJSONObject(it).getString("target")==destination)}
                check((0 until rules.length()).any {rules.getJSONObject(it).optString("geoPreset")=="geosite:google"})
                check((0 until rules.length()).any {rules.getJSONObject(it).has("presetIds")})
                passed.accept("$destination action commits visible service+geo rules, reloads encrypted fixture and avoids duplicates")
            }
            val saved=store.snapshot();val rules=saved.getJSONArray("rules");repeat(rules.length()){if(rules.getJSONObject(it).has("geoPreset"))rules.getJSONObject(it).put("enabled",false)}
            check(GeoDataConfig.neededSelectors(saved).isEmpty());saved.put("rules",JSONArray());check(GeoDataConfig.neededSelectors(saved).isEmpty())
            passed.accept("Disabled/deleted visible geo rules do not retain hidden geodata routing/assets")
        }finally{main {dialog?.dismiss();activity.finish()};idle();check(ProfileStore.snapshot(target)==before){"Preset fixture touched real user profiles/routes"}}
        passed.accept("Real user encrypted files unchanged; no native engine, account or external traffic used")
    }
}
