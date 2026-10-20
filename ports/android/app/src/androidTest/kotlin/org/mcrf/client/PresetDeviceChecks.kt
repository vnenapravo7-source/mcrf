package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicReference

object PresetDeviceChecks {
    @JvmStatic fun run(instrumentation:android.app.Instrumentation,temporary:File,passed:Consumer<String>) {
        val context=instrumentation.targetContext
        val before=ProfileStore.snapshot(context)
        val scoped=object:ContextWrapper(context){override fun getFilesDir()=temporary}
        val catalogue=PresetAssets.catalogue(context)
        check(catalogue.size==68 && catalogue.first().name=="AdAway — реклама и трекеры")
        passed.accept("All 68 Windows presets have readable UTF-8 names")
        val settings=RouteRules.defaults()
        val defaults=settings.getJSONArray("rules")
        val defaultIds=(0 until defaults.length()).map {defaults.getJSONObject(it).getString("id")}.toSet()
        catalogue.forEachIndexed { index,item -> settings.getJSONArray("rules").put(JSONObject().put("id","preset-$index").put("name",item.name)
            .put("text","").put("mode","addresses").put("target","proxy").put("presetIds",JSONArray().put(item.id))) }
        val files=PresetAssets.resolve(scoped,settings)
        val required=PresetRules.needed(settings).toSet()
        check(required.containsAll(catalogue.map {it.id}) && files.keys==required && files.values.all {File(it).isFile})
        passed.accept("Every compiled list resolves privately with its expected byte count and SHA-256")
        val source=ServerImport.build(ServerImport.read("socks://127.0.0.1:18888#Preset%20fixture"),ServerImport.read("socks://127.0.0.1:18888#Preset%20fixture").single().id,false,60,50,"Cloudflare")
        go.Seq.setContext(context.applicationContext)
        io.nekohasekai.libbox.Libbox.setup(io.nekohasekai.libbox.SetupOptions().apply {setBasePath(temporary.absolutePath);setWorkingPath(temporary.absolutePath);setTempPath(temporary.absolutePath)})
        val prepared=RouteRules.apply(ProfileConfig.build(source.toString(),false,18888),settings,false,files)
        io.nekohasekai.libbox.Libbox.checkConfig(prepared.toString())
        passed.accept("Android libbox accepts all 68 compiled rule-set references together")
        check(settings.toString().toByteArray().size<32768)
        val records=catalogue.filter {it.id in required}.sumOf {it.records.toLong()}
        passed.accept("$records ready routing entries fit in a saved reference profile smaller than 32KB, with ${defaultIds.size} preserved default rules")
        val item=catalogue.first();val file=File(files.getValue(item.id));file.writeBytes(byteArrayOf(1,2,3))
        PresetAssets.resolve(scoped,settings);check(file.length()==item.bytes)
        passed.accept("A damaged compiled cache is repaired from the signed app assets")
        val routes=RoutingStore(scoped).apply {load();save(settings)}
        val restored=RoutingStore(scoped).apply {load()}.snapshot().getJSONArray("rules")
        check(restored.length()==defaultIds.size+catalogue.size)
        val restoredIds=(0 until restored.length()).map {restored.getJSONObject(it).getString("id")}.toSet()
        check(restoredIds==defaultIds+catalogue.indices.map {"preset-$it"}.toSet())
        catalogue.forEachIndexed {index,preset ->val rule=(0 until restored.length()).map {restored.getJSONObject(it)}.single {it.getString("id")=="preset-$index"};check(PresetRules.ids(rule)==listOf(preset.id))}
        passed.accept("Preset references survive encrypted routing save and reload")
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var dialog:android.app.AlertDialog?=null
        fun onMain(block:()->Unit){val failure=AtomicReference<Throwable?>();instrumentation.runOnMainSync {try{block()}catch(error:Throwable){failure.set(error)}};failure.get()?.let {throw it}}
        try {
            val until=System.nanoTime()+10_000_000_000L;while(activity.working()&&System.nanoTime()<until)Thread.sleep(50)
            onMain {dialog=PresetPicker.show(activity,emptySet()){} }
            instrumentation.waitForIdleSync()
            val shown=requireNotNull(dialog)
            onMain {
                val root=shown.window!!.decorView
                root.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(320),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(activity.dp(560),View.MeasureSpec.EXACTLY));root.layout(0,0,root.measuredWidth,root.measuredHeight)
                check(root.width==activity.dp(320)&&shown.getButton(android.app.AlertDialog.BUTTON_POSITIVE).height>=activity.dp(48))
            }
            passed.accept("Searchable ready-list picker fits a 320dp dialog with accessible acceptance")
            onMain {dialog?.dismiss()};instrumentation.waitForIdleSync()
            val unchanged=routes.snapshot().toString();val unified=AtomicReference<android.app.AlertDialog?>()
            onMain {RoutingDefaultsPresetPicker.show(activity,routes){unified.set(it)}}
            val deadline=System.nanoTime()+10_000_000_000L;while(unified.get()==null&&System.nanoTime()<deadline)Thread.sleep(50)
            dialog=requireNotNull(unified.get());instrumentation.waitForIdleSync()
            fun <T:View> descendants(view:View,type:Class<T>):List<T>{val result=mutableListOf<T>();if(type.isInstance(view))result.add(type.cast(view)!!);if(view is android.view.ViewGroup)repeat(view.childCount){result.addAll(descendants(view.getChildAt(it),type))};return result}
            lateinit var types:android.widget.Spinner
            onMain {types=descendants(dialog!!.window!!.decorView,android.widget.Spinner::class.java).single();check(types.count==2);check(descendants(dialog!!.window!!.decorView,android.widget.CheckBox::class.java).size==RoutingDefaults.groups(catalogue).size);types.setSelection(1)}
            instrumentation.waitForIdleSync()
            onMain {val root=dialog!!.window!!.decorView;val boxes=descendants(root,android.widget.CheckBox::class.java);check(boxes.any {it.text.toString().contains("geoip:ru")});check(boxes.none {it.text.toString().contains("geoip:cn")});check(boxes.count {it.text.toString().contains("geosite:")}>=50);check(descendants(root,android.widget.Spinner::class.java).size==1);root.findViewWithTag<android.widget.EditText>("preset-search").setText(RoutingDefaults.groups(catalogue).first().first)}
            instrumentation.waitForIdleSync()
            onMain {val root=dialog!!.window!!.decorView;check(descendants(root,android.widget.CheckBox::class.java).any {it.tag.toString().startsWith("preset-row-service-")});types.setSelection(0);root.findViewWithTag<android.widget.EditText>("preset-search").setText("google")}
            instrumentation.waitForIdleSync()
            onMain {val root=dialog!!.window!!.decorView;check(root.findViewWithTag<View>("preset-row-geosite:google")!=null);root.findViewWithTag<android.widget.EditText>("preset-search").setText("");root.findViewWithTag<android.widget.ScrollView>("preset-scroll").scrollTo(0,10000);types.setSelection(1)}
            instrumentation.waitForIdleSync()
            onMain {val root=dialog!!.window!!.decorView;check(root.findViewWithTag<android.widget.ScrollView>("preset-scroll").scrollY==0);check(root.findViewWithTag<View>("preset-apply-direct")!=null&&root.findViewWithTag<View>("preset-apply-proxy")!=null&&root.findViewWithTag<View>("preset-apply-block")!=null);root.findViewWithTag<View>("preset-close").performClick()}
            instrumentation.waitForIdleSync();onMain {check(!dialog!!.isShowing)}
            check(routes.snapshot().toString()==unchanged)
            passed.accept("Merged Russia-only GeoIP/52 GeoSite, global cross-type search, scroll reset, three destination actions and close without writes")
        }finally{onMain {dialog?.dismiss();activity.finish()};instrumentation.waitForIdleSync()}
        check(ProfileStore.snapshot(context)==before)
        passed.accept("Native preset checks do not alter real user connections or routes")
    }
}
