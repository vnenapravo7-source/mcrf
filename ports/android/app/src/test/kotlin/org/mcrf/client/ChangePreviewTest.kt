package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChangePreviewTest {
    private fun values(prefs:String="{}"):Map<String,String> = ChangeHistoryStore.STORE_NAMES.associateWith {if(it=="preferences.enc")prefs else ""}
    @Test fun groupsAddedChangedRemovedWithoutAnySecretOrUntrustedLabel() {
        val before=values("""{"theme":"light","PRIVATE_REMOVED":"PRIVATE_A"}""")+
            ("connections.enc" to """{"servers":[{"id":"old","password":"PRIVATE_B"},{"id":"same","password":"PRIVATE_C"}]}""")
        val after=values("""{"theme":"dark","PRIVATE_ADDED":{"theme":"PRIVATE_D"}}""")+
            ("connections.enc" to """{"servers":[{"id":"new","password":"PRIVATE_E"},{"id":"same","password":"PRIVATE_F"}]}""")
        val preview=ChangePreview.prepare(before,after)
        assertEquals(ChangePreview.Kind.entries,preview.groups.map {it.kind})
        assertTrue(preview.groups.all {it.items.isNotEmpty()})
        assertTrue(preview.groups[0].items.contains("Серверы: 1"))
        assertTrue(preview.groups[1].items.contains("Серверы: 1"))
        assertTrue(preview.groups[2].items.contains("Серверы: 1"))
        val text=preview.groups.toString()+preview.toString()
        assertFalse(text.contains("PRIVATE"));assertFalse(text.contains("password"));assertFalse(text.contains("same"))
        assertFalse(text.contains("light"));assertFalse(text.contains("dark"))
    }
    @Test fun approvalCommitsTheDetachedSnapshotExactlyOnce() {
        val before=values();val after=values("{\"theme\":\"dark\"}").toMutableMap()
        val expected=after.toMap();val preview=ChangePreview.prepare(before,after)
        after["preferences.enc"]="{\"theme\":\"light\"}"
        var committed:Map<String,String>?=null
        preview.apply({before},{},{committed=it})
        assertEquals(expected,committed)
        assertThrows(IllegalStateException::class.java){preview.apply({before},{},{fail("replayed")})}
    }
    @Test fun staleBeforeOrDuringValidationNeverCommits() {
        val before=values();val newer=values("{\"selected\":\"changed\"}")
        val preview=ChangePreview.prepare(before,values("{\"theme\":\"dark\"}"))
        var current=before;var writes=0
        current=newer
        assertThrows(IllegalArgumentException::class.java){preview.apply({current},{},{writes++})}
        current=before
        assertThrows(IllegalArgumentException::class.java){preview.apply({current},{current=newer},{writes++})}
        assertEquals(0,writes)
    }
    @Test fun preparationAndCancelledHistoryRestoreHaveNoWrites() {
        class Memory:LocalDiagnosticStorage {
            var value="";override fun read()=value;override fun write(value:String){this.value=value};override fun delete(){value=""}
        }
        val memory=Memory();val history=ChangeHistoryStore(memory)
        val old=values("{\"theme\":\"light\"}");val current=values("{\"theme\":\"dark\"}")
        history.checkpoint(old);val stored=memory.value
        val request=history.prepareRestore(history.summaries(current).single().id,current)
        assertEquals(stored,memory.value)
        assertFalse(history.restore(request,{current},{false},{fail("cancel validated")},{fail("cancel committed")}))
        assertEquals(stored,memory.value)
        assertTrue(history.restore(request,{current},{true},{},{assertEquals(old,it)}))
    }
    @Test fun validatorCannotMutateTheSnapshotUsedByCommit() {
        val before=values();val after=values("{\"theme\":\"dark\"}")
        ChangePreview.prepare(before,after).apply({before},{candidate->(candidate as MutableMap<String,String>)["profile.enc"]="PRIVATE"},{assertEquals(after,it)})
    }
    @Test fun legacyBackupCredentialsArePreparedWithoutMutatingDestinationAndAppliedExactly() {
        val before=values();val backup=values("{\"theme\":\"dark\"}")
        val detached=AndroidBackup.prepareRestoreValues(backup,before)
        val credentials=JSONObject(detached.getValue("preferences.enc")).getJSONObject("localProxy").toString()
        assertFalse(JSONObject(before.getValue("preferences.enc")).has("localProxy"))
        assertFalse(JSONObject(backup.getValue("preferences.enc")).has("localProxy"))
        ChangePreview.prepare(before,detached).apply({before},{},{snapshot->
            assertEquals(credentials,JSONObject(snapshot.getValue("preferences.enc")).getJSONObject("localProxy").toString())
        })
        val second=AndroidBackup.prepareRestoreValues(backup,detached)
        assertEquals(credentials,JSONObject(second.getValue("preferences.enc")).getJSONObject("localProxy").toString())
    }
}
