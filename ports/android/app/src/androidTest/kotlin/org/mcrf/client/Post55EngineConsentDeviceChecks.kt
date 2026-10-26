package org.mcrf.client

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.function.Consumer

/** Actual private preferences and kernel gate in a disposable target UID.
 * Directly injects handler inputs, NOT real Android results/process death.
 * No package installation, provider calls, module hashing, VPN or UI launch. */
object Post55EngineConsentDeviceChecks {
    @JvmStatic fun run(base:Context,temporary:File,passed:Consumer<String>) {
        check(base.packageName=="org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode>=10056)
        check(Application.getProcessName()==base.applicationInfo.processName)
        check(!RemoteServerOperationGate.busy()&&!EngineUpdates.isBusy()&&!EngineUpdateScripts.mutationPending())
        check("fixture" !in EngineModules.allKinds)
        val live=EngineUpdates::class.java.getDeclaredField("livePreparedToken").apply{isAccessible=true}
        check(live.get(EngineUpdates)==null) {"An existing preparation must not be overwritten by the fixture"}
        check(temporary.canonicalFile.parentFile==base.cacheDir.canonicalFile&&!temporary.exists()&&temporary.mkdir())
        val ownCache=File(temporary,"cache").apply{check(mkdir())}
        val ownNoBackup=File(temporary,"no-backup").apply{check(mkdir())}
        val preferenceName="mcrf-post55-consent-${UUID.randomUUID()}"
        val prefs=base.getSharedPreferences(preferenceName,Context.MODE_PRIVATE)
        var rejectJournalRemoval=false
        val guarded=object:SharedPreferences by prefs {
            override fun edit():SharedPreferences.Editor {
                val editor=prefs.edit();var removesJournal=false
                return object:SharedPreferences.Editor by editor {
                    override fun putString(key:String,value:String?)=apply{editor.putString(key,value)}
                    override fun remove(key:String)=apply{if(key=="consentJournal")removesJournal=true;editor.remove(key)}
                    override fun clear()=apply{removesJournal=true;editor.clear()}
                    // This is a pre-delegate failure, not fsync/power-loss emulation.
                    override fun commit()=if(rejectJournalRemoval&&removesJournal)false else editor.commit()
                }
            }
        }
        val isolated=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getCacheDir()=ownCache
            override fun getNoBackupFilesDir()=ownNoBackup
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                check(name=="engine-updates") {"Unexpected preferences access"}
                return guarded
            }
        }
        val recover=EngineUpdates::class.java.getDeclaredMethod("recoverNow",Context::class.java).apply{isAccessible=true}
        fun recover() {
            try{recover.invoke(EngineUpdates,isolated)}catch(error:InvocationTargetException){throw (error.cause?:error)}
        }
        fun verify(ok:Boolean,message:String){check(ok){message};passed.accept(message)}
        fun usable():Boolean {
            val usage=EngineUpdateGate.acquireUsage(isolated)?:return false
            usage.close();return true
        }
        fun withMutation(action:(EngineUpdateGate.Pending)->Unit) {
            check(prefs.edit().clear().putString("phase","CONSENT").commit())
            // Unknown fixture kind makes gateReceipt return false BEFORE package
            // lookup/hash. It cannot be mistaken for a real installed engine.
            val lease=requireNotNull(EngineUpdateGate.beginMutation(isolated,"openflux_update","apk","fixture","a".repeat(64),17))
            try{action(lease.pending)}finally{
                rejectJournalRemoval=false
                try{
                    val pending=EngineUpdateGate.pending(isolated)
                    check(pending==null||pending.token==lease.pending.token)
                    if(pending!=null)check(EngineUpdateGate.complete(isolated,pending.token){true})
                }finally{lease.close()}
            }
        }
        fun journal(pending:EngineUpdateGate.Pending,stage:EngineConsentJournal.Stage) {
            check(prefs.edit().putString("consentJournal",EngineConsentJournal.Entry(pending.token,stage).encode()).commit())
        }
        var oldRequest=0
        try {
            withMutation { pending ->
                oldRequest=pending.requestCode
                val sentinel=File(ownCache,"owned-sentinel.apk").apply{writeBytes(byteArrayOf(1,2,3))}
                check(prefs.edit().putString("installerFile",sentinel.name).commit())
                EngineUpdates.abortPreparedConsent(isolated,pending.token)
                val cancelled=EngineUpdates.cancel(isolated)
                recover()
                verify(!cancelled&&EngineUpdateGate.pending(isolated)?.token==pending.token&&!usable()&&sentinel.readBytes().contentEquals(byteArrayOf(1,2,3)),
                    "Missing journal retains the real private gate and queued bytes despite CONSENT UI phase")
            }
            withMutation { pending ->
                journal(pending,EngineConsentJournal.Stage.LAUNCHING)
                EngineUpdates.abortPreparedConsent(isolated,pending.token)
                verify(!EngineUpdates.cancel(isolated)&&EngineUpdateGate.pending(isolated)?.token==pending.token&&!usable(),
                    "LAUNCHING cannot be aborted by ordinary UI cancellation or a false CONSENT phase")
            }
            withMutation { pending ->
                journal(pending,EngineConsentJournal.Stage.PREPARED)
                val stale=UUID.randomUUID().toString()
                EngineUpdates.abortPreparedConsent(isolated,stale);EngineUpdates.installerLaunchFailed(isolated,stale)
                verify(EngineUpdateGate.pending(isolated)?.token==pending.token&&EngineConsentJournal.parse(prefs.getString("consentJournal",null))?.token==pending.token,
                    "Stale preparation and failed-launch callbacks cannot clear a replacement token")
            }
            withMutation { pending ->
                journal(pending,EngineConsentJournal.Stage.PREPARED)
                check(prefs.edit().putString("phase","INSTALLING").commit())
                EngineUpdates.abortPreparedConsent(isolated,pending.token)
                verify(EngineUpdateGate.pending(isolated)==null&&!prefs.contains("consentJournal")&&usable(),
                    "Exact never-launched evidence releases the kernel gate independently of a misleading UI phase")
            }
            withMutation { pending ->
                journal(pending,EngineConsentJournal.Stage.PREPARED)
                check(prefs.edit().putString("phase","INSTALLING").commit())
                recover() // No live token: models reconstruction, not an actual killed/recreated process.
                verify(EngineUpdateGate.pending(isolated)==null&&!prefs.contains("consentJournal")&&usable(),
                    "Actual recovery handler clears matching PREPARED evidence with no live owner")
            }
            withMutation { pending ->
                journal(pending,EngineConsentJournal.Stage.PREPARED)
                val before=prefs.getString("consentJournal",null)
                rejectJournalRemoval=true
                val failed=try{EngineUpdates.abortPreparedConsent(isolated,pending.token);false}catch(_:IllegalStateException){true}
                rejectJournalRemoval=false
                verify(failed&&EngineUpdateGate.pending(isolated)==null&&prefs.getString("consentJournal",null)==before&&usable(),
                    "Rejected journal cleanup does not retain a released kernel lease or claim journal persistence")
            }
            withMutation { pending ->
                journal(pending,EngineConsentJournal.Stage.LAUNCHING)
                check(oldRequest!=pending.requestCode)
                EngineUpdates.applyInstallerResult(isolated,oldRequest,Activity.RESULT_CANCELED)
                verify(EngineUpdateGate.pending(isolated)?.token==pending.token&&prefs.contains("consentJournal")&&!usable(),
                    "Injected late Android request code cannot cancel a different outstanding installer")
            }
            withMutation { pending ->
                journal(pending,EngineConsentJournal.Stage.LAUNCHING)
                EngineUpdates.applyInstallerResult(isolated,pending.requestCode,Activity.RESULT_CANCELED)
                verify(EngineUpdateGate.pending(isolated)==null&&!prefs.contains("consentJournal")&&usable(),
                    "Injected matching system cancellation clears only its exact journal and mutation")
            }
            withMutation { pending ->
                journal(pending,EngineConsentJournal.Stage.LAUNCHING)
                EngineUpdates.applyInstallerResult(isolated,pending.requestCode,Activity.RESULT_OK)
                verify(EngineUpdateGate.pending(isolated)?.token==pending.token&&prefs.contains("consentJournal")&&!usable(),
                    "Injected RESULT_OK without an exact installed APK receipt cannot unblock engine usage")
            }
        } finally {
            rejectJournalRemoval=false
            check(EngineUpdateGate.pending(isolated)==null)
            check(base.deleteSharedPreferences(preferenceName))
        }
    }
}
