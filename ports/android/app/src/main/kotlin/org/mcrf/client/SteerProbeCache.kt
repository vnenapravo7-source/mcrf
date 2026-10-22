package org.mcrf.client

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/** The typed map cannot accidentally be merged into sing-box's persisted pings. */
internal class SteerProbeDelays(values:Map<String,Long>,completedAt:Map<String,Long>,val moduleSha:String,val networkHandle:Long):Map<String,Long> by java.util.Collections.unmodifiableMap(LinkedHashMap(values)) {
    val completedAt:Map<String,Long> = java.util.Collections.unmodifiableMap(LinkedHashMap(completedAt))
    init {require(values.keys==completedAt.keys&&values.values.all {it>=-1}&&completedAt.values.all {it>=0}&&moduleSha.matches(Regex("[0-9a-f]{64}"))&&networkHandle>0)}
}

/** Volatile presentation hints only. Actual automatic admission always probes
 * afresh; no saved/generic result is a native Steer connectivity proof. */
internal object SteerProbeCache {
    private data class Snapshot(val path:String,val apkSha:String,val network:Long,val at:Long,val wall:Long,
        val fingerprints:Map<String,String>,val delays:Map<String,Long>,val completedAt:Map<String,Long>)
    @Volatile private var latest:Snapshot?=null
    private data class Presentation(val source:Snapshot,val entries:List<ServerImport.Server>,val checkedAt:Long,val json:String)
    @Volatile private var presentation:Presentation?=null
    private val pending=java.util.concurrent.atomic.AtomicBoolean()
    private val reader=java.util.concurrent.Executors.newSingleThreadExecutor {work->Thread(work,"mcrf-steer-hints").apply {isDaemon=true}}
    fun publish(path:String,sha:String,network:Long,entries:List<ServerImport.Server>,values:SteerProbeDelays) {
        require(sha==values.moduleSha&&network==values.networkHandle&&values.keys==entries.map {it.id}.toSet())
        latest=Snapshot(path,sha,network,SystemClock.elapsedRealtime(),System.currentTimeMillis(),
            entries.associate {it.id to SteerProbePlan.fingerprint(it)},values.toMap(),values.completedAt.toMap())
    }
    fun clear(){latest=null;presentation=null}
    fun hints(context:Context,entries:List<ServerImport.Server>):JSONObject {
        val source=latest?:return JSONObject();val age=SystemClock.elapsedRealtime()-source.at
        if(age !in 0..300000)return JSONObject()
        val ready=presentation
        if(ready!=null&&ready.source===source&&ready.entries===entries&&SystemClock.elapsedRealtime()-ready.checkedAt in 0..1500)
            return JSONObject(ready.json)
        // Package/network Binder queries and effective-profile hashing stay
        // off the UI thread and outside the catalogue's synchronized caller.
        if(pending.compareAndSet(false,true)) {
            val app=context.applicationContext
            reader.execute {
                var changed=false
                try {
                    val json=readHints(app,source,entries).toString()
                    if(latest===source) {
                        val old=presentation
                        presentation=Presentation(source,entries,SystemClock.elapsedRealtime(),json)
                        changed=old==null||old.source!==source||old.entries!==entries||old.json!=json
                    }
                }catch(_:Exception){if(latest===source)presentation=Presentation(source,entries,SystemClock.elapsedRealtime(),"{}")}
                finally{pending.set(false)}
                if(changed)ProbeCoordinator.presentationChanged()
            }
        }
        // A short presentation-only grace avoids clearing/repainting every
        // unchanged ping while the asynchronous validation is in flight.
        return if(ready!=null&&ready.source===source&&ready.entries===entries&&SystemClock.elapsedRealtime()-ready.checkedAt in 0..5000)
            JSONObject(ready.json) else JSONObject() // Never automatic admission.
    }
    private fun readHints(context:Context,source:Snapshot,entries:List<ServerImport.Server>):JSONObject {
        val installed=try {context.packageManager.getApplicationInfo(EngineUpdatePrimary.contract("steer").packageName,0).sourceDir}catch(_:Exception){null}
        if(installed!=source.path||DirectAccessCheck.physical(context)?.networkHandle!=source.network)return JSONObject()
        return JSONObject().apply {entries.forEach {entry->
            val completed=source.completedAt[entry.id]
            if(completed!=null&&SystemClock.elapsedRealtime()-completed in 0..300000&&source.fingerprints[entry.id]==SteerProbePlan.fingerprint(entry))source.delays[entry.id]?.let {delay->
                put(entry.id,JSONObject().put("delay",delay).put("at",source.wall-(source.at-completed)).put("backend","steer").put("apkSha256",source.apkSha))
            }
        }}
    }
}
