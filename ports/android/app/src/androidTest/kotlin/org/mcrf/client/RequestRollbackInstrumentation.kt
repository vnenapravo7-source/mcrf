package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import android.view.accessibility.AccessibilityNodeInfo
import android.os.ParcelFileDescriptor

/** Owned emulator: real ZIP preparation + document picker cancellation. Never uninstalls or restores. */
class RequestRollbackInstrumentation:Instrumentation() {
    private var withInviteIdentity=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);withInviteIdentity=arguments?.getString("withInviteIdentity")=="true";start()}
    private fun main(action:()->Unit){val error=AtomicReference<Throwable?>();runOnMainSync {try{action()}catch(e:Throwable){error.set(e)}};error.get()?.let {throw it}}
    private fun field(host:VersionHistoryActivity,name:String):Any? {
        val value=AtomicReference<Any?>()
        main {value.set(VersionHistoryActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(host))}
        return value.get()
    }
    private fun await(message:String,condition:()->Boolean){val end=System.nanoTime()+60_000_000_000L;while(System.nanoTime()<end){if(condition())return;Thread.sleep(50)};error(message)}
    private fun find(view:View,predicate:(View)->Boolean):View? {
        if(predicate(view))return view
        if(view is ViewGroup)repeat(view.childCount){find(view.getChildAt(it),predicate)?.let {found->return found}}
        return null
    }
    private fun window(predicate:(View)->Boolean)=android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {find(it,predicate)}
    private fun node(root:AccessibilityNodeInfo,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        if(predicate(root))return root
        repeat(root.childCount){root.getChild(it)?.let {child->node(child,predicate)?.let {return it}}}
        return null
    }
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}
    private fun sha(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}};return digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}}
    private fun prepare(host:VersionHistoryActivity,entry:VersionArchiveStore.Entry,finishImmediately:Boolean=false) {
        main {
            val version=find(host.window.decorView){it is Button&&it.text.toString().contains("${entry.version} · ${entry.code}")} as? Button
                ?:error("Saved version button missing")
            check(version.performClick())
            val save=window {it is Button&&it.text.toString().equals("Сохранить комплект",true)} as? Button
                ?:error("Save bundle confirmation button missing")
            check(save.performClick())
        }
        // AlertDialog dispatches button callbacks through a Message, after performClick returns.
        waitForIdleSync()
        await("Save bundle click did not start preparation") {field(host,"busy")==true||field(host,"pending")!=null}
        main {
            check(window {it is EditText&&it.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD!=0}==null){"Rollback must not ask for a password"}
            if(finishImmediately)host.finish()
        }
    }

    override fun onStart() {
        val report=Bundle();var host:VersionHistoryActivity?=null;var exported:String?=null;var originalStores:Map<String,String>?=null
        try {
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            shell("am force-stop com.android.documentsui")
            if(withInviteIdentity){
                originalStores=ProfileStore.snapshot(targetContext)
                val value=org.json.JSONObject(originalStores!!.getValue("preferences.enc").ifBlank {"{}"})
                    .put("inviteDeviceId","12345678-1234-1234-1234-123456789abc")
                    .put("inviteCode",org.json.JSONObject().put("token","owned-fixture-token").put("lastSync",System.currentTimeMillis()))
                ProfileStore(targetContext,"preferences.enc").write(value.toString())
            }
            val before=ProfileStore.snapshot(targetContext)
            val entry=VersionArchive.capture(targetContext,targetContext.packageName)
            host=startActivitySync(Intent(targetContext,VersionHistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VersionHistoryActivity
            val activity=host!!;await("History load timed out"){field(activity,"busy")==false}
            prepare(activity,entry)
            var file:File?=null
            await("Bundle preparation timed out"){
                val name=field(activity,"pending") as? String
                if(name==null){
                    if(field(activity,"busy")==false){
                        val label=field(activity,"status") as android.widget.TextView
                        var message="";main {message=label.text.toString()}
                        error("Bundle preparation failed: "+ClientEvents.redact(message))
                    }
                    false
                } else {file=File(targetContext.cacheDir,name);file!!.isFile}
            }
            val bundle=requireNotNull(file)
            val names=linkedSetOf<String>()
            ZipInputStream(bundle.inputStream()).use {zip->
                while(true){val item=zip.nextEntry?:break;check(names.add(item.name))
                    when(item.name){
                        "MCRF.apk","MCRF-current.apk"->{val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);var bytes=0L;while(true){val n=zip.read(buffer);if(n<0)break;digest.update(buffer,0,n);bytes+=n};check(bytes==entry.bytes&&digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}==entry.sha)}
                        "MCRF-backup.enc"->{val encrypted=zip.readBytes();check(BackupCodec.isPortable(encrypted));val plain=BackupCodec.unpack(encrypted);try{check(AndroidBackup.validate(org.json.JSONObject(plain.toString(Charsets.UTF_8))).keys==setOf("profile.enc","connections.enc","routing.enc","preferences.enc"))}finally{plain.fill(0);encrypted.fill(0)}}
                        "README.txt"->check(zip.readBytes().toString(Charsets.UTF_8).contains("MCRF-current.apk"))
                        else->error("Unexpected rollback entry")
                    };zip.closeEntry()
                }
            }
            check(names==setOf("MCRF.apk","MCRF-current.apk","MCRF-backup.enc","README.txt"))
            await("Document picker did not open"){uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Cancellation retained temporary ZIP"){field(activity,"pending")==null&&!bundle.exists()}
            prepare(activity,entry)
            var saved:File?=null
            await("Second bundle preparation timed out"){
                val name=field(activity,"pending") as? String
                if(name==null)false else {saved=File(targetContext.cacheDir,name);saved!!.isFile}
            }
            val expectedSha=sha(requireNotNull(saved))
            await("Save picker did not open"){uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
            val picker=requireNotNull(uiAutomation.rootInActiveWindow)
            check(node(picker){it.text?.toString()=="Downloads"}!=null){"Owned export requires Downloads picker root"}
            val filename="mcrf-rollback-fixture-${java.util.UUID.randomUUID()}.zip"
            exported="/sdcard/Download/$filename"
            val edit=requireNotNull(node(picker){it.className?.toString()=="android.widget.EditText"})
            check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,filename)}))
            val save=requireNotNull(node(requireNotNull(uiAutomation.rootInActiveWindow)){it.className?.toString()=="android.widget.Button"&&it.text?.toString()?.equals("Save",true)==true})
            check(save.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val status=field(activity,"status") as android.widget.TextView
            await("SAF save did not finish") {var text="";main{text=status.text.toString()};text.startsWith("Комплект сохранён")&&field(activity,"pending")==null&&!saved!!.exists()}
            check(shell("sha256sum $exported").substringBefore(' ')==expectedSha){"Externally saved ZIP differs"}
            shell("rm -- $exported");exported=null
            prepare(activity,entry)
            await("Failure fixture bundle preparation timed out"){field(activity,"pending")!=null}
            val rejected=File(targetContext.cacheDir,field(activity,"pending") as String)
            main {
                VersionHistoryActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply {isAccessible=true}
                    .invoke(activity,101,Activity.RESULT_OK,Intent().setData(android.net.Uri.parse("content://org.mcrf.missing-owned-fixture/denied")))
            }
            await("Failed destination was silent or leaked ZIP") {
                var message="";main {message=status.text.toString()}
                message.startsWith("Не удалось")&&field(activity,"pending")==null&&!rejected.exists()
            }
            main {
                check(window {it is android.widget.TextView&&it.text.toString()=="Не удалось выполнить действие"}!=null)
                (window {it is Button&&it.text.toString().equals("ОК",true)} as? Button?:error("Error acknowledgement missing")).performClick()
            };waitForIdleSync()
            val existing=targetContext.cacheDir.listFiles().orEmpty().filter {it.name.startsWith("mcrf-rollback-")}.map {it.name}.toSet()
            prepare(activity,entry,true)
            await("Abandoned preparation retained worker"){field(activity,"busy")==false}
            check(targetContext.cacheDir.listFiles().orEmpty().none {it.name.startsWith("mcrf-rollback-")&&it.name !in existing})
            check(ProfileStore.snapshot(targetContext)==before){"Rollback preview changed saved profiles"}
            report.putString("result","PASS: real ZIP APK hashes/recovery APK/password-free backup/guide, actual Save button + SAF save external SHA, rejected destination visible error, cancel cleanup, destroyed Activity cleanup; saved profiles unchanged")
            finish(Activity.RESULT_OK,report)
        }catch(e:Throwable){android.util.Log.e("McrfRollbackTest","Rollback regression failed",e);report.putString("failure",e.javaClass.name+": "+e.message);finish(Activity.RESULT_CANCELED,report)}
        finally{if(uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true)sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);exported?.let {shell("rm -- $it")};host?.let {activity->main {activity.finish()}};originalStores?.let {ProfileStore.replaceAll(targetContext,it)}}
    }
}
