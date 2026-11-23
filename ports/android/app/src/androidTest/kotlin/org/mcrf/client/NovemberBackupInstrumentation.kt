package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicReference

/** Owned emulator: export/validate/preview/cancel only; never applies a restore. */
class NovemberBackupInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=Bundle();var host:MainActivity?=null
        fun main(action:()->Unit){val failure=AtomicReference<Throwable?>();runOnMainSync {try{action()}catch(e:Throwable){failure.set(e)}};failure.get()?.let {throw it}}
        fun hasEditor(view:View):Boolean=view is EditText||view is ViewGroup&&(0 until view.childCount).any {hasEditor(view.getChildAt(it))}
        try {
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            host=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity=host!!
            val deadline=System.nanoTime()+15_000_000_000L
            while(activity.working()&&System.nanoTime()<deadline)Thread.sleep(50)
            check(!activity.working())
            val bytes=AndroidBackup.export(targetContext,charArrayOf())
            try {check(BackupCodec.isPortable(bytes));AndroidBackup.prepareRestore(targetContext,bytes,charArrayOf())}finally {bytes.fill(0)}
            var confirmation:AlertDialog?=null;var preparationShown=false
            main {
                BackupUi(activity){dialog->
                    if(dialog.findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString()=="Подготавливаем бэкап…")preparationShown=true
                    else confirmation=dialog
                }.export()
                check(confirmation!=null&&!hasEditor(confirmation!!.window!!.decorView))
            }
            waitForIdleSync()
            main {
                check(!activity.working())
                confirmation!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                check(preparationShown)
            }
            val exportDeadline=System.nanoTime()+20_000_000_000L
            while(activity.working()&&System.nanoTime()<exportDeadline)Thread.sleep(50)
            check(!activity.working());Thread.sleep(700)
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(500)
            report.putString("result","PASS: password-free export and restore preview; confirmation has no password; preparation visible; document picker cancelled; no restore applied")
            finish(Activity.RESULT_OK,report)
        }catch(e:Throwable){report.putString("failure",e.javaClass.name+": "+e.message+" at "+e.stackTrace.firstOrNull());finish(Activity.RESULT_CANCELED,report)}
        finally {host?.let {main {it.finish()}}}
    }
}
