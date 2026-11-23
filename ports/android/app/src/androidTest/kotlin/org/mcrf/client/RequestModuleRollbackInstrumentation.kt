package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference

/** Only our fresh DEBUG Xray on an owned emulator. Never touches the main application package. */
class RequestModuleRollbackInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun main(action:()->Unit){val error=AtomicReference<Throwable?>();runOnMainSync{try{action()}catch(e:Throwable){error.set(e)}};error.get()?.let {throw it}}
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}
    private fun await(message:String,condition:()->Boolean){val end=System.nanoTime()+45_000_000_000L;while(System.nanoTime()<end){if(condition())return;Thread.sleep(50)};error(message)}
    private fun field(host:VersionHistoryActivity,name:String):Any? {val value=AtomicReference<Any?>();main{value.set(VersionHistoryActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(host))};return value.get()}
    private fun node(root:AccessibilityNodeInfo,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {if(predicate(root))return root;repeat(root.childCount){root.getChild(it)?.let {child->node(child,predicate)?.let{return it}}};return null}
    private fun installerButton(vararg labels:String):AccessibilityNodeInfo? {
        val root=uiAutomation.rootInActiveWindow?:return null
        if(root.packageName?.toString()?.contains("packageinstaller")!=true)return null
        return node(root){it.className?.toString()=="android.widget.Button"&&labels.any {label->it.text?.toString()?.equals(label,true)==true}}
    }
    private fun installed():Boolean=try{targetContext.packageManager.getApplicationInfo("org.mcrf.engines.xray",0);true}catch(_:PackageManager.NameNotFoundException){false}
    override fun onStart() {
        val report=Bundle();var host:VersionHistoryActivity?=null;var rescue:String?=null;var previousMode:String?=null
        try {
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            val app=targetContext;val info=EngineModules.installation(app,"xray")
            check(info.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0){"Only the fresh owned debug module may be reinstalled"}
            check(info.metaData?.getString("org.mcrf.engines.source.commit")=="e662c22ab1096883c4df200a0e8068a89da04df1")
            val before=ProfileStore.snapshot(app)
            val entry=VersionArchive.capture(app,info.packageName)
            rescue="/data/local/tmp/mcrf-xray-rollback-${java.util.UUID.randomUUID()}.apk"
            check(info.sourceDir.matches(Regex("/data/app/[A-Za-z0-9_./~+=-]+/base\\.apk")))
            shell("cp -- ${info.sourceDir} $rescue")
            check(shell("sha256sum $rescue").substringBefore(' ')==entry.sha)
            previousMode=Regex("REQUEST_INSTALL_PACKAGES: ([a-z_]+)").find(shell("appops get ${app.packageName} REQUEST_INSTALL_PACKAGES"))?.groupValues?.get(1)?:"default"
            check(previousMode in setOf("allow","default","deny","ignore","foreground"))
            shell("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow")
            host=startActivitySync(Intent(app,VersionHistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VersionHistoryActivity
            val activity=host!!;await("History not loaded"){field(activity,"busy")==false}
            fun invoke(name:String){main{VersionHistoryActivity::class.java.getDeclaredMethod(name,VersionArchiveStore.Entry::class.java).apply{isAccessible=true}.invoke(activity,entry)}}
            requireNotNull(EngineUpdateGate.acquireUsage(app)).use {
                invoke("remove");await("Busy refusal did not finish"){field(activity,"busy")==false}
                check(installed()&&EngineUpdateGate.pending(app)==null){"History mutation bypassed active core usage"}
                val label=field(activity,"status") as android.widget.TextView;var text="";main{text=label.text.toString()};check(text.contains("используется"))
            }
            invoke("remove")
            await("Uninstall confirmation missing"){installerButton("OK","Uninstall")!=null}
            val removal=requireNotNull(EngineUpdateGate.pending(app));check(removal.owner=="version_history"&&removal.action=="remove"&&removal.kind=="xray")
            check(EngineUpdateGate.acquireUsage(app)==null){"Native run admitted behind uninstall confirmation"}
            check(requireNotNull(installerButton("OK","Uninstall")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Owned debug module not removed"){!installed()}
            await("Uninstall receipt did not release gate"){EngineUpdateGate.pending(app)==null}
            await("History reload not complete"){field(activity,"busy")==false}
            invoke("install")
            await("Install confirmation missing"){installerButton("Install")!=null}
            val installation=requireNotNull(EngineUpdateGate.pending(app));check(installation.owner=="version_history"&&installation.action=="apk"&&installation.kind=="xray"&&installation.digest==entry.sha)
            check(installation.requestCode!=removal.requestCode&&EngineUpdateGate.acquireUsage(app)==null)
            val temporary=field(activity,"pendingInstall") as String
            check(requireNotNull(installerButton("Install")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Archive module did not install"){installed()}
            await("Installed APK receipt did not release gate"){
                installerButton("Done")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                EngineUpdateGate.pending(app)==null
            }
            check(VersionArchive.capture(app,info.packageName).sha==entry.sha){"Reinstalled APK differs from selected archive"}
            check(!java.io.File(app.cacheDir,temporary).exists()&&field(activity,"pendingInstall")==null)
            check(ProfileStore.snapshot(app)==before){"Module rollback changed saved profiles"}
            report.putString("result","PASS: active usage blocks rollback; actual Android uninstall+archive reinstall, distinct request receipts, usage blocked throughout confirmation, exact installed APK hash, temporary cleanup; main profiles unchanged")
            finish(Activity.RESULT_OK,report)
        }catch(e:Throwable){android.util.Log.e("McrfModuleRollback","Module rollback regression failed",e);report.putString("failure",e.javaClass.name+": "+e.message);finish(Activity.RESULT_CANCELED,report)}
        finally {
            // Restore ONLY the owned debug module if a failed UI check left it absent.
            rescue?.let {path->if(!installed())shell("pm install -r $path");shell("rm -- $path")}
            previousMode?.let {shell("appops set ${targetContext.packageName} REQUEST_INSTALL_PACKAGES $it")}
            host?.let {activity->main{activity.finish()}}
        }
    }
}
