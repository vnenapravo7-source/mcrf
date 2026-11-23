package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.function.Consumer

/** Explicit fixture entry point; never uses the user's catalogue, routes or preferences. */
object HistoryRestoreAtomicDeviceChecks {
    @JvmStatic fun run(base:Context,temporary:File,passed:Consumer<String>) {
        val root=File(temporary,"history-restore-${UUID.randomUUID()}").also {check(it.mkdir())}
        val preferenceName="history-restore-fixture-${UUID.randomUUID()}"
        val isolated=object:ContextWrapper(base) {
            override fun getFilesDir()=root
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(preferenceName,mode)
        }
        fun verify(condition:Boolean,label:String){check(condition){label};passed.accept(label)}
        try {
            val rawBefore=ProfileStore.snapshot(isolated)
            val initial=ProfileStore.historySnapshot(isolated)
            verify(initial.getValue("connections.enc").isNotEmpty()&&initial.getValue("routing.enc").isNotEmpty(),
                "Uninitialized stores produce complete restore points")
            verify(rawBefore==ProfileStore.snapshot(isolated),"Capturing defaults does not write live stores")
            RoutingStore.validate(JSONObject(initial.getValue("routing.enc")))
            val original=initial+("preferences.enc" to JSONObject().put("theme","dark").toString())
            ProfileStore.withoutHistory {ProfileStore.replaceAll(isolated,original)}
            val generation=ProfileStore.generation(isolated)
            val broken=original+("routing.enc" to "x".repeat(ServerImport.MAX_BYTES+1))
            var failed=false
            try {ProfileStore.withoutHistory {ProfileStore.replaceAll(isolated,broken)}}catch(_:IOException){failed=true}
            verify(failed&&ProfileStore.generation(isolated)==generation&&ProfileStore.snapshot(isolated)==original,
                "Failed staging keeps all four original files and active pointer unchanged")
            // The atomic generation is independent of the UI/history validators.
            // Verify the history coordinator around the real encrypted implementation.
            val history=ChangeHistoryStore.local(isolated)
            val before=ProfileStore.historySnapshot(isolated)
            val target=before+("preferences.enc" to JSONObject().put("theme","light").toString())
            check(history.checkpoint(target))
            val request=history.prepareRestore(history.summaries(before).first().id,before)
            history.restore(request,{ProfileStore.historySnapshot(isolated)},{true},{ChangeHistoryStore.requireCompleteSnapshot(it)},{
                ProfileStore.withoutHistory {ProfileStore.replaceAll(isolated,it)}
            })
            verify(ProfileStore.snapshot(isolated)==target,"Restore publishes the complete staged generation")
            val reopened=ChangeHistoryStore.local(isolated)
            reopened.restore(reopened.prepareUndo(ProfileStore.historySnapshot(isolated)),{ProfileStore.historySnapshot(isolated)},
                {true},{ChangeHistoryStore.requireCompleteSnapshot(it)},{ProfileStore.withoutHistory {ProfileStore.replaceAll(isolated,it)}})
            verify(ProfileStore.snapshot(isolated)==before,"Durable encrypted undo restores every file exactly")
        }finally {base.deleteSharedPreferences(preferenceName)}
    }
}
