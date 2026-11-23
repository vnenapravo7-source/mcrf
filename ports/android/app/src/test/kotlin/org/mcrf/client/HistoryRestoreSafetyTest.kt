package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HistoryRestoreSafetyTest {
    private class Memory:LocalDiagnosticStorage {
        var raw="";var reject=false;var ignore=false
        override fun read()=raw
        override fun write(value:String) {if(reject)throw IOException("disk full");if(!ignore)raw=value}
        override fun delete(){raw=""}
    }
    private fun values(theme:String)=mapOf(
        "profile.enc" to "",
        "connections.enc" to ConnectionCatalogue("").serialize(),
        "routing.enc" to RouteRules.defaults().toString(),
        "preferences.enc" to JSONObject().put("theme",theme).toString())
    private fun request(history:ChangeHistoryStore,current:Map<String,String>)=
        history.prepareRestore(history.summaries(current).single().id,current)

    @Test fun restorePinsExactFullBeforeSetAndUndoSurvivesReopenAndRetention() {
        val memory=Memory();var history=ChangeHistoryStore(memory,maxCount=1)
        val target=values("light");val original=values("dark")+("routing.enc" to RouteRules.defaults().put("defaultTarget","direct").toString())
        var live=original;history.checkpoint(target)
        history.restore(request(history,live),{live},{true},{},{
            // Safety must already be durable before any configuration write.
            assertTrue(ChangeHistoryStore(memory).hasUndo());live=it
        })
        assertEquals(target,live)
        repeat(20){history.checkpoint(values(if(it%2==0)"system" else "dark"))}
        history=ChangeHistoryStore(memory,maxCount=1)
        val undo=history.prepareUndo(live)
        history.restore(undo,{live},{true},{},{live=it})
        assertEquals(original,live)
        assertThrows(IllegalStateException::class.java){history.restore(undo,{live},{true},{},{fail("replayed")})}
    }
    @Test fun missingOrUnwritableSafetyNeverCallsCommit() {
        for(ignore in listOf(false,true)) {
            val memory=Memory();val history=ChangeHistoryStore(memory)
            val original=values("dark");var current=original;history.checkpoint(values("light"));val request=request(history,current)
            memory.ignore=ignore;memory.reject=!ignore
            assertThrows(Exception::class.java){history.restore(request,{current},{true},{},{current=it})}
            assertEquals(original,current)
        }
    }
    @Test fun insufficientUndoSpaceRefusesRestoreWithoutTouchingDataOrHistory() {
        val memory=Memory();val history=ChangeHistoryStore(memory)
        assertTrue(history.checkpoint(values("light")));val current=values("dark")+("profile.enc" to "x".repeat(ChangeHistoryStore.MAX_BYTES))
        val request=request(history,current);val raw=memory.raw
        assertThrows(IllegalStateException::class.java){history.restore(request,{current},{true},{},{fail("Oversize restore committed")})}
        assertEquals(raw,memory.raw);assertFalse(history.hasUndo())
    }
    @Test fun failedAtomicCommitLeavesOriginalAndDurableUndoIntact() {
        val memory=Memory();val history=ChangeHistoryStore(memory);val original=values("dark")
        var current=original;history.checkpoint(values("light"))
        assertThrows(IOException::class.java){history.restore(request(history,current),{current},{true},{},{throw IOException("staging failed")})}
        assertEquals(original,current)
        val expected=original
        history.restore(history.prepareUndo(current),{current},{true},{},{assertEquals(expected,it);current=it})
        assertEquals(original,current)
    }
    @Test fun cancelledOrStaleUndoNeverCommitsOrOverwritesSafety() {
        val memory=Memory();val history=ChangeHistoryStore(memory);var current=values("dark")
        history.checkpoint(values("light"));history.restore(request(history,current),{current},{true},{},{current=it})
        val undo=history.prepareUndo(current);val raw=memory.raw
        assertFalse(history.restore(undo,{current},{false},{fail("cancel validated")},{fail("cancel committed")}))
        assertEquals(raw,memory.raw)
        current=values("system")
        assertThrows(IllegalArgumentException::class.java){history.restore(undo,{current},{true},{},{fail("stale undo committed")})}
        assertEquals(raw,memory.raw)
    }
    @Test fun failedUndoKeepsItsOriginalTargetForRetry() {
        val memory=Memory();val history=ChangeHistoryStore(memory);val original=values("dark")
        var current=original
        history.checkpoint(values("light"))
        history.restore(request(history,current),{current},{true},{},{current=it})
        val undo=history.prepareUndo(current)
        assertThrows(IOException::class.java){history.restore(undo,{current},{true},{},{throw IOException("staging failed")})}
        history.restore(history.prepareUndo(current),{current},{true},{},{current=it})
        assertEquals(original,current)
    }
    @Test fun legacyMissingRouteOrCatalogueCannotPassRestoreGate() {
        val full=values("light");ChangeHistoryStore.requireCompleteSnapshot(full)
        for(key in listOf("connections.enc","routing.enc")) {
            assertThrows(IllegalArgumentException::class.java){ChangeHistoryStore.requireCompleteSnapshot(full+(key to ""))}
            assertThrows(IllegalArgumentException::class.java){ChangeHistoryStore.requireCompleteSnapshot(full-key)}
        }
    }
    @Test fun preferenceRecorderRejectsMixedAndObsoleteRestoreGenerations() {
        fun prefs(generation:String)=mapOf("restoreAppliedGeneration" to generation)
        assertTrue(ChangeHistoryRecorder.sameGeneration(emptyMap<String,String>(),emptyMap<String,String>(),""))
        assertTrue(ChangeHistoryRecorder.sameGeneration(prefs("new"),prefs("new"),"new"))
        assertFalse(ChangeHistoryRecorder.sameGeneration(prefs("old"),prefs("new"),"new"))
        assertFalse(ChangeHistoryRecorder.sameGeneration(prefs("old"),prefs("old"),"new"))
    }
    @Test fun queuedCheckpointCannotWriteAfterRestoreGenerationChanged() {
        val memory=Memory();val history=ChangeHistoryStore(memory)
        history.checkpoint(values("light"));val raw=memory.raw
        assertFalse(history.checkpointIfCurrent(values("dark")){false})
        assertEquals(raw,memory.raw)
    }
    @Test fun pendingRestoreNeverMixesOldAndroidPreferencesIntoNewGeneration() {
        val restored=JSONObject().put("theme","dark").put("automatic",true)
        val old=mapOf("theme" to "light","automatic" to false,"restoreAppliedGeneration" to "old")
        ProfileStore.mergeHistoryPreferences(restored,old,"new")
        assertEquals("dark",restored.getString("theme"));assertTrue(restored.getBoolean("automatic"))
        ProfileStore.mergeHistoryPreferences(restored,old+("restoreAppliedGeneration" to "new"),"new")
        assertEquals("light",restored.getString("theme"));assertFalse(restored.getBoolean("automatic"))
    }
    @Test fun corruptedUndoCannotBeSilentlyOverwrittenByNextCheckpoint() {
        val memory=Memory();val history=ChangeHistoryStore(memory)
        history.checkpoint(values("light"))
        memory.raw=JSONObject(memory.raw).put("undo","corrupt").toString()
        val raw=memory.raw
        assertThrows(IllegalArgumentException::class.java){history.checkpoint(values("dark"))}
        assertEquals(raw,memory.raw)
    }
}
