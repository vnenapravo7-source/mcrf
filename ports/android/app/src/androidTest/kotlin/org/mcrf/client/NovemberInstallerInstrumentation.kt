package org.mcrf.client

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference

/** Owned emulator only: reinstalls its identical bundled BS APK through Android.
 * A held usage lease simulates a draining engine; no provider or VPN is started. */
class NovemberInstallerInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun main(action:()->Unit){val error=AtomicReference<Throwable?>();runOnMainSync {try{action()}catch(t:Throwable){error.set(t)}};error.get()?.let {throw it}}
    private fun await(message:String,test:()->Boolean){val end=System.nanoTime()+30_000_000_000L;while(System.nanoTime()<end){if(test())return;Thread.sleep(40)};error(message)}
    private fun node(root:AccessibilityNodeInfo,test:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        if(test(root))return root
        repeat(root.childCount){root.getChild(it)?.let {child->node(child,test)?.let {return it}}}
        return null
    }
    override fun onStart() {
        val result=Bundle();var host:MainActivity?=null;var usage:EngineUpdateGate.Usage?=null
        try {
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            check(targetContext.packageManager.canRequestPackageInstalls()){"Owned emulator needs install permission"}
            val before=ProfileStore.snapshot(targetContext)
            host=startActivitySync(Intent(targetContext,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity=host!!;await("Activity did not load"){!activity.working()}
            check(EngineUpdates.confirmPendingReceipt(targetContext)){"Previous owned installer receipt is unresolved"}
            val importer=MainActivity::class.java.getDeclaredField("engineImport").apply {isAccessible=true}.get(activity) as EngineImportUi
            fun dialog()=EngineImportUi::class.java.getDeclaredField("dialog").apply {isAccessible=true}.get(importer) as? AlertDialog
            val assets=BundledEngines.available(targetContext,setOf("bs"));check(assets.size==1)
            usage=requireNotNull(EngineUpdateGate.acquireUsage(targetContext))
            main {importer.showUpdates(assets)};waitForIdleSync()
            val window=requireNotNull(dialog())
            main {window.getButton(AlertDialog.BUTTON_POSITIVE).performClick()}
            await("Busy-engine action did not appear"){importer.state?.phase=="deferred-install"}
            waitForIdleSync();Thread.sleep(350)
            main {
                check(dialog()===window)
                val button=window.getButton(AlertDialog.BUTTON_POSITIVE)
                check(button.text.toString()=="Отключить и установить"&&button.isEnabled)
                button.performClick()
                check(requireNotNull(importer.state).stopDeadline>0)
            }
            usage!!.close();usage=null
            await("Android installer did not open") {uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("packageinstaller")==true}
            main {check(dialog()===window)}
            var install:AccessibilityNodeInfo?=null
            await("Install/update button did not become ready") {
                install=uiAutomation.rootInActiveWindow?.let {root->node(root) {
                    it.className?.toString()=="android.widget.Button"&&listOf("Install","Update").any {label->it.text?.toString()?.equals(label,true)==true}
                }}
                install!=null
            }
            check(requireNotNull(install).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Installer result was not consumed"){var done=false;main {done=importer.state==null};done}
            check(EngineUpdateGate.pending(targetContext)==null)
            check(EnginePackages.installed(targetContext,assets.single()))
            check(ProfileStore.snapshot(targetContext)==before){"Install changed saved profiles"}
            result.putString("result","PASS: one MCRF dialog through offer/download/busy stop; one stop-and-install tap; Android installer confirmed identical bundled BS; mutation retired; profiles unchanged; no provider traffic")
            finish(Activity.RESULT_OK,result)
        }catch(t:Throwable){result.putString("failure",t.javaClass.name+": "+t.message+" at "+t.stackTrace.firstOrNull());finish(Activity.RESULT_CANCELED,result)}
        finally {
            usage?.close()
            if(uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("packageinstaller")==true){sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitForIdleSync();Thread.sleep(400)}
            host?.let {main {it.finish()}}
        }
    }
}
