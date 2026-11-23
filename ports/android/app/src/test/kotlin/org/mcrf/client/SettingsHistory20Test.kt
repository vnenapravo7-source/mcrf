package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsHistory20Test {
    private class Memory:LocalDiagnosticStorage {var value="";override fun read()=value;override fun write(value:String){this.value=value};override fun delete(){value=""}}
    private fun values(theme:String,recovery:Boolean)=ChangeHistoryStore.STORE_NAMES.associateWith {
        if(it=="preferences.enc")JSONObject().put("theme",theme).put("generalOptions",JSONObject().put("autoRecoveryEnabled",recovery)).toString()else ""
    }
    @Test fun entriesDescribeAdjacentEditsWhileRestoreDescribesReverseDifference() {
        val history=ChangeHistoryStore(Memory());val first=values("light",true);val second=values("dark",true);val current=values("dark",false)
        history.checkpoint(first);history.checkpoint(second)
        val summaries=history.summaries(current)
        assertEquals(listOf("Подключение · Автовосстановление: вкл → выкл"),summaries[0].changes)
        assertEquals(listOf("Тема: светлая → тёмная"),summaries[1].changes)
        assertTrue(summaries[0].restoreChanges.contains("Подключение · Автовосстановление: выкл → вкл"))
    }
    @Test fun historyDoesNotRenderSecretsAddressesOrUntrustedFieldNames() {
        val before=values("light",true)
        val after=before.toMutableMap().apply {
            put("profile.enc","PRIVATE_PROFILE_SENTINEL")
            put("preferences.enc",JSONObject().put("theme","dark").put("PRIVATE_KEY_SENTINEL",JSONObject().put("token","PRIVATE_VALUE_SENTINEL")).toString())
            put("connections.enc","{\"servers\":[\"https://PRIVATE_ADDRESS_SENTINEL\"],\"edits\":{\"secret\":\"PRIVATE_EDIT_SENTINEL\"}}")
        }
        val changes=ChangeHistoryDiff.describe(before,after).joinToString("\n")
        assertTrue(changes.contains("светлая → тёмная"));assertTrue(changes.contains("Сохранённые серверы: 0 → 1"))
        assertFalse(changes.contains("PRIVATE"));assertFalse(changes.contains("https://"))
    }
    @Test fun staleCheckedSnapshotCannotRestoreOrCommitAndValidationHasNoWrites() {
        val memory=Memory();val history=ChangeHistoryStore(memory);val first=values("light",true)
        var current=values("dark",true);history.checkpoint(first)
        val request=history.prepareRestore(history.summaries(current).single().id,current)
        val before=memory.value;var validations=0;var commits=0
        history.validateRestore(request,{current},{validations++});assertEquals(1,validations);assertEquals(before,memory.value)
        current=values("dark",false)
        assertThrows(IllegalArgumentException::class.java){history.restore(request,{current},{true},{},{commits++})}
        assertEquals(0,commits);assertEquals(before,memory.value)
    }
    @Test fun nativeValidationDoesNotHoldStorageLockAndCommitStillDoes() {
        val lock=Any();val history=ChangeHistoryStore(Memory(),lock=lock)
        val before=values("light",true);var current=values("dark",true)
        history.checkpoint(before)
        val request=history.prepareRestore(history.summaries(current).single().id,current)
        history.validateRestore(request,{current},{assertFalse(Thread.holdsLock(lock))})
        assertTrue(history.restore(request,{current},{true},{assertFalse(Thread.holdsLock(lock))},{
            assertTrue(Thread.holdsLock(lock));current=it
        }))
        assertEquals(before,current)
    }
    @Test fun editDuringDetachedValidationRejectsCommit() {
        val history=ChangeHistoryStore(Memory());val before=values("light",true);var current=values("dark",true)
        history.checkpoint(before)
        val request=history.prepareRestore(history.summaries(current).single().id,current)
        assertThrows(IllegalArgumentException::class.java) {
            history.restore(request,{current},{true},{current=values("dark",false)},{fail("Stale snapshot committed")})
        }
    }
    @Test fun historyDiffDoesNotCompileGeoRulesAndStillRecordsRuleEdits() {
        val before=values("light",true)+("routing.enc" to """{"rules":[{"id":"one","text":"geoip:youtube","enabled":true}]}""")
        val after=before+("routing.enc" to """{"rules":[{"id":"one","text":"geosite:ru","enabled":true}]}""")
        val history=ChangeHistoryStore(Memory());history.checkpoint(before)
        assertTrue(history.summaries(after).single().changes.any {it.contains("правило")})
    }
    @Test fun openingAnotherRouteGroupDoesNotCreateHistory() {
        val root=RouteRules.defaults();RoutingGroups.ensure(root)
        val before=values("light",true)+("routing.enc" to root.toString())
        RoutingGroups.select(root,"bs")
        val after=before+("routing.enc" to root.toString())
        assertTrue(ChangeHistoryStore.equivalent("routing.enc",before.getValue("routing.enc"),after.getValue("routing.enc")))
        assertTrue(ChangeHistoryDiff.describe(before,after).isEmpty())
    }
    @Test fun removedSubscriptionServersAndRenamedServersHaveSafeNamesAndIds() {
        val link="vless://00000000-0000-0000-0000-000000000001@fixture.example:443#Frankfurt"
        val server=ServerImport.read(link).single()
        val base=values("light",true)
        val catalogue=JSONObject().put("servers",org.json.JSONArray()).put("subscriptions",org.json.JSONArray().put(
            JSONObject().put("id","subscription").put("url","https://private.example/SECRET_TOKEN")
                .put("servers",org.json.JSONArray().put(link))))
        val before=base+("connections.enc" to catalogue.toString())
        val removed=ChangeHistoryDiff.describe(before,base).joinToString("\n")
        assertTrue(removed.contains("Сервер удалён: «Frankfurt» [${server.id.take(8)}]"))
        catalogue.put("edits",JSONObject().put(server.id,JSONObject().put("name","Berlin").put("password","SECRET_PASSWORD")))
        val changed=ChangeHistoryDiff.describe(before,base+("connections.enc" to catalogue.toString())).joinToString("\n")
        assertTrue(changed.contains("Frankfurt"));assertTrue(changed.contains("Berlin"))
        assertFalse((removed+changed).contains("SECRET"));assertFalse((removed+changed).contains("fixture.example"))
        assertFalse((removed+changed).contains("vless://"))
    }
    @Test fun pastedCredentialInServerNameIsHidden() {
        val link="vless://00000000-0000-0000-0000-000000000001@fixture.example:443#token%3DVERY_SECRET_VALUE"
        val before=values("light",true)
        val after=before+("connections.enc" to JSONObject().put("servers",org.json.JSONArray().put(link)).toString())
        val result=ChangeHistoryDiff.describe(before,after).joinToString("\n")
        assertTrue(result.contains("Сервер добавлен: ["));assertFalse(result.contains("VERY_SECRET_VALUE"))
    }
    @Test fun preferenceSnapshotsKeepEncryptedSectionsAndRecordOnlyActualSettings() {
        val base=values("light",true)
        val (before,after)=ChangeHistoryRecorder.snapshots(base,mapOf("theme" to "light","selected" to "one"),
            mapOf("theme" to "dark","selected" to "two"),setOf("theme","selected"))
        assertEquals(JSONObject(base.getValue("preferences.enc")).getJSONObject("generalOptions").toString(),
            JSONObject(after.getValue("preferences.enc")).getJSONObject("generalOptions").toString())
        val changes=ChangeHistoryDiff.describe(before,after)
        assertEquals(listOf("Тема: светлая → тёмная"),changes)
        val history=ChangeHistoryStore(Memory());history.checkpoint(before);history.checkpoint(after)
        assertEquals(changes,history.summaries(after).single().changes)
    }
}
