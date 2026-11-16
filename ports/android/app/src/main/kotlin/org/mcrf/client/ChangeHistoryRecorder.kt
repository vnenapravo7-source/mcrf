package org.mcrf.client

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.Executors

/** Plain Android preferences bypass ProfileStore.write. Retain their before/after values too.
 * The listener only copies small maps; encrypted snapshots and diffs run on one background worker. */
internal class ChangeHistoryRecorder private constructor(
    private val context:Context, private val history:ChangeHistoryStore
) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
    private val guard=Any()
    private var previous=preferences.all.toMap()
    private data class Delta(val before:Map<String,*>,val after:Map<String,*>)
    private var pending:Delta?=null
    private var running=false
    private val worker=Executors.newSingleThreadExecutor {task->Thread(task,"mcrf-change-history").apply {isDaemon=true}}
    init {preferences.registerOnSharedPreferenceChangeListener(this)}

    override fun onSharedPreferenceChanged(prefs:SharedPreferences,key:String?) {
        synchronized(guard) {
            val next=prefs.all.toMap();val before=previous;previous=next
            // Applying a restored generation is one transaction, not a new edit of
            // the old preferences against the newly restored catalogue/routes.
            if(before["restoreAppliedGeneration"]!=next["restoreAppliedGeneration"]) {pending=null;return}
            val keys=AndroidBackup.preferenceKeys()
            if(key!=null&&key !in keys)return
            if(keys.all {before[it]==next[it]})return
            pending=Delta(pending?.before?:before,next)
            if(running)return
            running=true
            worker.execute(::drain)
        }
    }
    private fun drain() {
        while(true) {
            val delta=synchronized(guard) {val next=pending;pending=null;if(next==null)running=false;next}?:return
            try {
                val base=synchronized(ProfileStore.storageLock) {
                    if(sameGeneration(delta.before,delta.after,ProfileStore.generation(context)))ChangeHistoryStore.snapshotLocal(context) else null
                }?:continue
                val (before,after)=snapshots(base,delta.before,delta.after,AndroidBackup.preferenceKeys())
                if(ChangeHistoryStore.equivalent("preferences.enc",before.getValue("preferences.enc"),after.getValue("preferences.enc")))continue
                fun stillCurrent()=sameGeneration(delta.before,delta.after,ProfileStore.generation(context))
                history.checkpointIfCurrent(before,::stillCurrent)
                history.checkpointIfCurrent(after,::stillCurrent)
            } catch(_:Exception) {
                // Optional history may fail; never interrupt the configuration writer or log values.
                ClientEvents.record(context,"Клиент","Не удалось сохранить изменение настроек в локальной истории.")
            }
        }
    }
    companion object {
        // SharedPreferences holds listeners weakly; keep exactly one recorder per private data directory.
        private val recorders=java.util.concurrent.ConcurrentHashMap<String,ChangeHistoryRecorder>()
        internal fun sameGeneration(before:Map<String,*>,after:Map<String,*>,current:String):Boolean =
            (before["restoreAppliedGeneration"] as? String).orEmpty()==current &&
                (after["restoreAppliedGeneration"] as? String).orEmpty()==current
        fun observe(context:Context,history:ChangeHistoryStore) {
            val app=context.applicationContext
            recorders.computeIfAbsent(app.filesDir.absolutePath) {ChangeHistoryRecorder(app,history)}
        }
        internal fun snapshots(base:Map<String,String>,before:Map<String,*>,after:Map<String,*>,keys:Set<String>):Pair<Map<String,String>,Map<String,String>> {
            fun withPreferences(values:Map<String,*>)=base+("preferences.enc" to JSONObject(base.getValue("preferences.enc").ifBlank {"{}"}).apply {
                keys.forEach {key->remove(key);values[key]?.let {put(key,it)}}
            }.toString())
            return withPreferences(before) to withPreferences(after)
        }
    }
}
