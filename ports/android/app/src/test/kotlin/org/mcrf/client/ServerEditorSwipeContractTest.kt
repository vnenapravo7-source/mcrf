package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerEditorSwipeContractTest {
    private val source="vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#Original"
    private fun catalogue()=ConnectionCatalogue("").apply {add(source)}

    @Test fun whitelistOverridePreservesSourceIdentityCredentialsAndEdits() {
        val data=catalogue();val entry=data.entries().single()
        val edited=ConnectionEdits.snapshot(entry).put("name","Local")
        edited.getJSONObject("outbound").put("server","changed.example")
        data.edit(entry.id,edited);data.setAuto(entry.id,true)
        val before=JSONObject(data.serialize());val moved=ServerSectionChoice.move(before,entry.id)
        assertFalse(before.has(ServerSectionChoice.KEY))
        val restored=ConnectionCatalogue(moved.toString());val result=restored.entries().single()
        assertEquals(entry.id,result.id);assertEquals(source,result.original)
        assertEquals("Local",result.name);assertEquals("changed.example",result.host)
        assertEquals(data.autoIds(),restored.autoIds())
        assertTrue(ServerSectionChoice.belongs(result,true,ServerSectionChoice.assignments(moved)))
        assertFalse(ServerSectionChoice.belongs(result,false,ServerSectionChoice.assignments(moved)))
        assertEquals(moved.toString(),ServerSectionChoice.move(moved,entry.id).toString())
    }
    @Test fun assignmentSurvivesSubscriptionRefreshAndLocalReset() {
        val data=ConnectionCatalogue("").apply {replaceSubscription("https://example.com/sub",ServerImport.read(source))}
        val id=data.entries().single().id
        val assigned=ConnectionCatalogue(ServerSectionChoice.move(JSONObject(data.serialize()),id).toString())
        assigned.replaceSubscription("https://example.com/sub",ServerImport.read(source))
        assigned.edit(id,null)
        assertEquals(setOf(id),ServerSectionChoice.whitelistIds(JSONObject(assigned.serialize())))
    }
    @Test fun removedOrMalformedAssignmentCannotChangeCatalogue() {
        val data=catalogue();val before=JSONObject(data.serialize())
        assertThrows(IllegalArgumentException::class.java){ServerSectionChoice.move(before,"0".repeat(64))}
        assertEquals(data.serialize(),before.toString())
        assertThrows(IllegalArgumentException::class.java){ServerSectionChoice.whitelistIds(JSONObject().put(ServerSectionChoice.KEY,JSONObject().put("bad-id","bs")))}
        assertThrows(IllegalArgumentException::class.java){ServerSectionChoice.whitelistIds(JSONObject().put(ServerSectionChoice.KEY,JSONObject().put(data.entries().single().id,true)))}
    }
    @Test fun editorRejectsDeletedAndConcurrentlyChangedServer() {
        val data=catalogue();val entry=data.entries().single();val expected=ConnectionEdits.snapshot(entry)
        assertSame(entry,ServerEditorCommit.requireUnchanged(entry,expected))
        assertThrows(IllegalArgumentException::class.java){ServerEditorCommit.requireUnchanged(null,expected)}
        data.edit(entry.id,JSONObject(expected.toString()).put("name","Other editor"))
        assertThrows(IllegalArgumentException::class.java){ServerEditorCommit.requireUnchanged(data.entries().single(),expected)}
    }
    @Test fun shareReadsCommittedEditAndDoesNotExposeSecretsInObjectDescription() {
        val data=catalogue();val entry=data.entries().single()
        data.edit(entry.id,ConnectionEdits.snapshot(entry).put("name","Latest"))
        val payload=ServerSharePayload.current(ConnectionCatalogue(data.serialize()),entry.id)
        assertEquals("Latest",payload.name)
        assertEquals("Latest",ServerImport.read(payload.source).single().name)
        assertFalse(payload.toString().contains("11111111-1111"))
        data.remove(entry.id)
        assertThrows(IllegalArgumentException::class.java){ServerSharePayload.current(data,entry.id)}
    }
    @Test fun shareChoicesSeparateOriginalLinkFromSettingsAndPairEncryptionWithQr() {
        val choices=ServerShareOptions.values()
        assertEquals(5,choices.size)
        assertFalse(ServerShareOptions.SETTINGS_LINK.encrypted)
        assertFalse(ServerShareOptions.SETTINGS_LINK.qr)
        assertTrue(ServerShareOptions.SETTINGS_LINK.title.contains("настройками MCRF"))
        assertEquals(setOf(false to false,false to true,true to false,true to true),choices.map {it.encrypted to it.qr}.toSet())
        choices.filter {it.encrypted}.forEach {assertTrue(it.title.contains("только для MCRF"))}
    }
}
