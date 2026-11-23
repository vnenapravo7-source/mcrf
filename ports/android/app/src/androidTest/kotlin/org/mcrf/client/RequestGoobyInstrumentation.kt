package org.mcrf.client

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Owned emulator only: real editors, synthetic unsaved profiles, no provider. */
class RequestGoobyInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=Bundle();var code=Activity.RESULT_CANCELED;var host:MainActivity?=null;var dialog:AlertDialog?=null
        fun main(action:()->Unit){val error=AtomicReference<Throwable?>();runOnMainSync{try{action()}catch(t:Throwable){error.set(t)}};error.get()?.let{throw it};waitForIdleSync()}
        fun rows(view:View):List<CarrierOption> = if(view is CarrierOption)listOf(view) else if(view is ViewGroup)(0 until view.childCount).flatMap{rows(view.getChildAt(it))} else emptyList()
        try {
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            val before=ProfileStore.snapshot(targetContext)
            val metadata=targetContext.packageManager.getApplicationInfo("org.mcrf.engines.bs",android.content.pm.PackageManager.GET_META_DATA).metaData
            check(metadata.getInt(CsqttGooby.CAPABILITY)==1){"CURRENT_BS_GOOBY_CAPABILITY_REQUIRED"}
            host=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity=host!!;val deadline=android.os.SystemClock.elapsedRealtime()+15000
            while(activity.working()&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50)
            check(!activity.working());var checks=0
            for(generation in listOf("2.5.1","2.5.2","2.5.3")) {
                val raw=JSONObject().put("type","csqtt").put("csqtt_generation","2.5").put("csqtt_implementation","swg-panel-$generation")
                    .put("name","Owned Gooby editor").put("server","192.0.2.1").put("server_port",56005).put("password","owned-unsaved-fixture")
                    .put("hashes",JSONArray().put("A".repeat(43))).put("vk_hash_mode","manual").put("workers",20).put("link_workers",20)
                    .put("fingerprint","chrome").put("obfs","unified").put("turn_transport","udp").put("captcha_mode","auto").put("vk_auth_mode","vkcalls")
                    .put("custom_turn_ips",JSONArray()).put("allow_hash_redistribution",false)
                val entry=ServerImport.Server("owned-gooby-$generation","Owned Gooby editor","csqtt","192.0.2.1",56005,null,null,null,raw)
                lateinit var editor:ConnectionEditor
                main {
                    editor=ConnectionEditor(activity,entry);dialog=editor.show()
                    // Disable persistence ONLY for this synthetic fixture. Real
                    // option callbacks, readers and capability checks still run.
                    ConnectionEditor::class.java.getDeclaredField("watching").apply{isAccessible=true}.setBoolean(editor,false)
                    dialog!!.window!!.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
                val form=dialog!!.window!!.decorView.findViewWithTag<View>("connection-editor-form")!!
                val turn=rows(form).single{(it.getChildAt(1) as TextView).text.toString()=="Транспорт TURN"}
                val gooby=form.findViewWithTag<CarrierOption>("csqtt-gooby-option")!!
                main {check(gooby.visibility==View.GONE);turn.setSelection(1);check(gooby.visibility==View.VISIBLE);ServerEditorLayout.reveal(gooby,form)}
                for(index in 1..3)main {
                    gooby.setSelection(index)
                    @Suppress("UNCHECKED_CAST")
                    val readers=ConnectionEditor::class.java.getDeclaredField("readers").apply{isAccessible=true}.get(editor) as List<()->Unit>
                    readers.forEach{it()}
                    val edited=ConnectionEditor::class.java.getDeclaredField("options").apply{isAccessible=true}.get(editor) as JSONObject
                    val profile=edited.getJSONObject("carrier");val mode=CsqttGooby.strategies[index-1]
                    check(CsqttGooby.active(profile)&&CsqttGooby.strategy(profile)==mode)
                    NativeCarrier.requireOptions(activity,profile)
                    val runtime=Csqtt25Profile.privateConfig(profile,22001,22002,"owned","owned","owned")
                    check(runtime.getBoolean("gooby_enabled")&&runtime.optString("gooby_strategy","current")==mode);checks++
                }
                main {gooby.requestRectangleOnScreen(android.graphics.Rect(0,0,gooby.width,gooby.height),true)}
                main {for(index in listOf(1,2)){val text=gooby.getChildAt(index) as TextView;val layout=requireNotNull(text.layout)
                    check(layout.lineCount<=3&&layout.getLineEnd(layout.lineCount-1)==text.length())
                    if(index==2)check(layout.lineCount==1){"GOOBY_PRESET_WORD_WRAPPED"}
                    check(layout.height<=text.height-text.totalPaddingTop-text.totalPaddingBottom)
                    for(line in 0 until layout.lineCount)check(layout.getEllipsisCount(line)==0)
                }}
                if(generation=="2.5.3"){
                    Thread.sleep(150);val image=requireNotNull(uiAutomation.takeScreenshot())
                    val folder=java.io.File(targetContext.getExternalFilesDir(null),"gooby-ui").apply{mkdirs()}
                    java.io.File(folder,"tcp-presets.png").outputStream().use{check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};image.recycle()
                }
                main {turn.setSelection(0);check(gooby.visibility==View.GONE);dialog!!.dismiss();dialog=null}
            }
            check(ProfileStore.snapshot(targetContext)==before){"GOOBY_EDITOR_CHANGED_SAVED_PROFILES"}
            report.putInt("presetChecks",checks);report.putString("result","PASS: real TCP/UDP visibility, nine preset cases, module capability and private config; no saved edits or provider calls")
            code=Activity.RESULT_OK
        }catch(t:Throwable){report.putString("failure",t.message?:t.javaClass.simpleName)}
        finally{main{dialog?.dismiss();host?.finish()}}
        finish(code,report)
    }
}
