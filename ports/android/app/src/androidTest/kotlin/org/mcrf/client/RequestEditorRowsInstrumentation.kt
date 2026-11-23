package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Real editors with synthetic, unsaved profiles; no provider calls or edits. */
class RequestEditorRowsInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart() {
        val report=Bundle();var code=Activity.RESULT_CANCELED
        var host:MainActivity?=null;var dialog:android.app.AlertDialog?=null
        fun main(action:()->Unit){val error=AtomicReference<Throwable?>();runOnMainSync {try{action()}catch(t:Throwable){error.set(t)}};error.get()?.let {throw it};waitForIdleSync()}
        fun rows(view:View):List<CarrierOption> = if(view is CarrierOption)listOf(view) else if(view is ViewGroup)(0 until view.childCount).flatMap {rows(view.getChildAt(it))}else emptyList()
        var count=0
        val defects=linkedSetOf<String>()
        try {
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            val before=ProfileStore.snapshot(targetContext)
            host=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity=host!!
            val deadline=android.os.SystemClock.elapsedRealtime()+15_000
            while(activity.working()&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50)
            check(!activity.working())
            for(kind in listOf("legacy","2.0","2.1.5","2.5.1","2.5.2","2.5.3","qwdtt","wdtt")) {
                val modern=kind.startsWith("2.5.")
                val type=if(kind in listOf("qwdtt","wdtt"))kind else "csqtt"
                val profile=JSONObject().put("type",type).put("server","192.0.2.1").put("server_port",56005)
                    .put("password","owned-unsaved-layout-fixture").put("hashes",org.json.JSONArray().put("A".repeat(43)))
                    .put("workers",20).put("fingerprint","chrome").put("obfs",if(modern)"unified"else"audio")
                    .put("vk_hash_mode","manual").put("hash_source","manual").put("vk_anon_path","vkcalls")
                    .put("captcha_mode","auto").put("allow_hash_redistribution",false)
                if(modern)profile.put("csqtt_generation","2.5").put("csqtt_implementation","swg-panel-$kind")
                    .put("vk_auth_mode","vkcalls").put("turn_transport","udp").put("custom_turn_ips",org.json.JSONArray())
                else if(type=="csqtt")profile.put("csqtt_generation",kind)
                val entry=ServerImport.Server("owned-layout-$kind","Layout $kind",type,"192.0.2.1",56005,null,null,null,profile)
                main {
                    dialog=ConnectionEditor(activity,entry).show()
                    // Only synthetic profiles exist in these test dialogs.
                    dialog!!.window!!.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
                val root=requireNotNull(dialog).window!!.decorView
                val form=requireNotNull(root.findViewWithTag<View>("connection-editor-form"))
                val options=rows(form).filter {it.visibility!=View.GONE};check(options.isNotEmpty())
                for(option in options) {
                    main {ServerEditorLayout.reveal(option,form)}
                    main {option.requestRectangleOnScreen(android.graphics.Rect(0,0,option.width,option.height),true)}
                    val valueView=option.getChildAt(2) as TextView
                    val originalValue=valueView.text
                    @Suppress("UNCHECKED_CAST")
                    val choices=CarrierOption::class.java.getDeclaredField("values").apply {isAccessible=true}.get(option) as List<CarrierOption.Value>
                    for(choice in choices) {
                    // Presentation only: don't call setSelection/changed/autosave.
                    main {valueView.text=choice.label}
                    main {
                        check(option.orientation==android.widget.LinearLayout.HORIZONTAL)
                        val label=option.getChildAt(1) as TextView
                        val value=option.getChildAt(2) as TextView
                        for(text in listOf(label,value)) {
                            val layout=requireNotNull(text.layout)
                            if(text.text.toString() in setOf("Выкл","Вкл"))check(layout.lineCount==1){"BOOLEAN_VALUE_WRAPPED"}
                            if(layout.lineCount>3||layout.getLineEnd(layout.lineCount-1)!=text.length())defects.add("TEXT $kind: ${text.text}")
                            if(layout.height>text.height-text.totalPaddingTop-text.totalPaddingBottom)defects.add("HEIGHT $kind: ${text.text}")
                            for(line in 0 until layout.lineCount)if(layout.getEllipsisCount(line)!=0||layout.getLineMax(line)>text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1)defects.add("WIDTH $kind: ${text.text}")
                        }
                        check(label.right<=value.left&&label.height>0&&value.height>0)
                        count++
                    }
                    }
                    main {valueView.text=originalValue}
                    if(kind=="2.5.3"&&(option.getChildAt(1) as TextView).text.toString()=="Маскировка") {
                        Thread.sleep(250)
                        val folder=java.io.File(targetContext.getExternalFilesDir(null),"editor-row-checks").apply {mkdirs()}
                        val image=requireNotNull(uiAutomation.takeScreenshot())
                        java.io.File(folder,"modern-masking.png").outputStream().use {check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
                        image.recycle()
                    }
                }
                main {dialog!!.dismiss();dialog=null}
            }
            check(ProfileStore.snapshot(targetContext)==before){"EDITOR_CHANGED_SAVED_PROFILES"}
            check(defects.isEmpty()){defects.joinToString(" | ")}
            report.putInt("rows",count);report.putString("result","PASS: actual unsaved SWG editors, eight versions/types, horizontal labels/values <=3 lines, full text and bounds; saved profile documents unchanged")
            code=Activity.RESULT_OK
        }catch(t:Throwable){report.putString("failure",t.message?:t.javaClass.simpleName)}
        finally {main {dialog?.dismiss();host?.finish()}}
        finish(code,report)
    }
}
