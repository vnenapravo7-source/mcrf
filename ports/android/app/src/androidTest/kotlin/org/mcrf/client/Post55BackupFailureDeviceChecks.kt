package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.json.JSONObject

/** New failure/locking paths only; never replay the accepted full backup suite. */
object Post55BackupFailureDeviceChecks {
    @JvmStatic fun run(base:Context,temporary:File,passed:Consumer<String>) {
        check(base.packageName=="org.mcrf.p3test") {"Isolated review UID required"}
        check(base.packageManager.getPackageInfo(base.packageName,0).longVersionCode>=10056) {"Old APK cannot verify post55 sources"}
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile==base.cacheDir.canonicalFile&&!temporary.exists()&&temporary.mkdir())
        val preferenceName="mcrf-post55-backup-${UUID.randomUUID()}"
        val prefs=base.getSharedPreferences(preferenceName,Context.MODE_PRIVATE)
        var rejectCommit=false
        val failingPrefs=object:SharedPreferences by prefs {
            override fun edit():SharedPreferences.Editor {
                val editor=prefs.edit()
                return object:SharedPreferences.Editor by editor {
                    // Preserve this wrapper through fluent calls; otherwise the final
                    // chained commit would bypass the injected failure.
                    override fun putString(key:String,value:String?)=apply {editor.putString(key,value)}
                    override fun putStringSet(key:String,values:MutableSet<String>?)=apply {editor.putStringSet(key,values)}
                    override fun putInt(key:String,value:Int)=apply {editor.putInt(key,value)}
                    override fun putLong(key:String,value:Long)=apply {editor.putLong(key,value)}
                    override fun putFloat(key:String,value:Float)=apply {editor.putFloat(key,value)}
                    override fun putBoolean(key:String,value:Boolean)=apply {editor.putBoolean(key,value)}
                    override fun remove(key:String)=apply {editor.remove(key)}
                    override fun clear()=apply {editor.clear()}
                    override fun commit():Boolean=if(rejectCommit)false else editor.commit()
                }
            }
        }
        val isolated=object:ContextWrapper(base) {
            override fun getFilesDir()=temporary
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                check(name=="MainActivity") {"Unexpected preferences access"}
                return failingPrefs
            }
        }
        fun verify(condition:Boolean,message:String){check(condition){message};passed.accept(message)}
        try {
            check(prefs.edit().putString("theme","light").putBoolean("wantedRunning",true).commit())
            val routes=RoutingStore(isolated);routes.load()
            val values=mapOf("profile.enc" to "", "connections.enc" to ConnectionCatalogue("").serialize(),
                "routing.enc" to routes.snapshot().toString(),"preferences.enc" to JSONObject().put("theme","dark").toString())
            val generation=ProfileStore.replaceAll(isolated,values)
            rejectCommit=true
            var rejected=false
            try {AndroidBackup.applyRestoredPreferences(isolated)}catch(_:IllegalStateException){rejected=true}
            verify(rejected&&!prefs.contains("restoreAppliedGeneration"),"Failed preferences commit leaves the restored generation pending")
            verify(ProfileStore.snapshot(isolated)==values&&ProfileStore.generation(isolated)==generation,
                "Post-commit preferences failure does not undo or rewrite the durable restored profile set")
            rejectCommit=false
            verify(AndroidBackup.applyRestoredPreferences(isolated)&&prefs.getString("theme","")=="dark"&&
                !prefs.getBoolean("wantedRunning",true)&&prefs.getString("restoreAppliedGeneration","")==generation,
                "Retry applies the pending generation and disables automatic connection intent")

            val entered=CountDownLatch(1)
            val finished=CountDownLatch(1)
            val revision=AtomicReference<String>()
            val error=AtomicReference<Throwable>()
            val reader=Thread({
                entered.countDown()
                try {revision.set(ProfileStore(isolated,"connections.enc").revision())}
                catch(failure:Throwable){error.set(failure)}finally{finished.countDown()}
            },"post55-owned-revision-reader")
            try {
                synchronized(ProfileStore.storageLock) {
                    reader.start()
                    check(entered.await(2,TimeUnit.SECONDS))
                    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
                    while(reader.state!=Thread.State.BLOCKED&&finished.count>0&&System.nanoTime()<deadline)Thread.yield()
                    verify(reader.state==Thread.State.BLOCKED&&finished.count==1L,
                        "Revision read waits for the same storage lock as generation replacement")
                }
            }finally {reader.join(5000)}
            verify(!reader.isAlive&&error.get()==null&&revision.get()?.substringBefore(':')==generation,
                "Released revision read resolves the committed generation without a pointer race")
        }finally {base.deleteSharedPreferences(preferenceName)}
    }
}
