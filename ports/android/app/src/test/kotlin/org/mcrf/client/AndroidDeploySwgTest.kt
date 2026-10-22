package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidDeploySwgTest {
    @Test fun legacyLongCallDraftOpensAndPreservesAllWdttCalls(){
        val calls=JSONArray().apply {repeat(12){put("retained-call-$it")}}
        val options=JSONObject().put("relay_kind","wdtt").put("vk_calls",calls)
        val profiles=AndroidDeploySwg.drafts(options);options.put("swg_profiles",profiles)
        val before=options.toString()
        AndroidDeployBackupSettings.validate(JSONObject().put("deployDrafts",JSONObject().put("swg",JSONObject().put("options",options))))
        assertEquals(12,profiles.getJSONObject(1).getJSONArray("vk_calls").length())
        assertEquals(12,AndroidDeploySwg.profiles(options).single().getJSONArray("vk_calls").length())
        assertEquals(before,options.toString())
    }
    @Test fun expandedCallListsHaveNoProtocolCountCapAndKeepPayloadGuard(){
        for(kind in AndroidDeploySwg.kinds)for(count in listOf(7,33,64)){
            val calls=JSONArray().apply {repeat(count){i->put("https://vk.ru/call/join/"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){i.toByte()}))}}
            val options=JSONObject().put("relay_kind",kind).put("vk_calls",calls)
            val profiles=AndroidDeploySwg.drafts(options);options.put("swg_profiles",profiles)
            AndroidDeploySwg.validateCalls(options)
            assertEquals(count,AndroidDeploySwg.profiles(options).single().getJSONArray("vk_calls").length())
            AndroidDeployBackupSettings.validate(JSONObject().put("deployDrafts",JSONObject().put("swg",JSONObject().put("options",options))))
            assertEquals(count,profiles.getJSONObject(AndroidDeploySwg.kinds.indexOf(kind)).getJSONArray("vk_calls").length())
        }
        val tooLarge=JSONArray().apply {repeat(100){put("a".repeat(1024))}}
        assertThrows(IllegalArgumentException::class.java){AndroidDeploySwg.validateCallArray(tooLarge)}
    }
    @Test fun panelOnlyHistoryDoesNotClaimUncreatedProtocolGroups() {
        val options=all().put("create",false)
        val target=AndroidDeployTarget("192.0.2.1",sshPassword="fixture",options=options)
        val record=AndroidDeployHistory.record(JSONObject().put("provision_requested",false),target,"swg")
        assertFalse(record.has("swg_profiles"))
    }
    @Test fun multiDraftSurvivesStrictEncryptedBackupValidation() {
        val options=all()
        val backup=JSONObject().put("deployDrafts",JSONObject().put("swg",JSONObject().put("options",options)))
        AndroidDeployBackupSettings.validate(backup)
        AndroidDeployBackupSettings.validate(JSONObject(backup.toString()))
        assertEquals(4,backup.getJSONObject("deployDrafts").getJSONObject("swg").getJSONObject("options").getJSONArray("swg_profiles").length())
    }
    @Test fun sameCallsCannotBeReusedAcrossProtocols() {
        val options=all()
        val call="https://vk.ru/call/join/"+java.util.Base64.getUrlEncoder().encodeToString(ByteArray(32){7})
        options.getJSONArray("swg_profiles").let {a ->repeat(a.length()){a.getJSONObject(it).put("vk_calls",JSONArray().put(call))}}
        assertThrows(IllegalArgumentException::class.java){AndroidDeploySwg.validateCalls(options)}
    }
    @Test fun savedAutomaticCallsMustBelongToExactProtocolGroup() {
        val options=JSONObject().put("swg_profiles",AndroidDeploySwg.drafts(JSONObject()))
        val array=options.getJSONArray("swg_profiles");val p=array.getJSONObject(0)
        val hash=java.util.Base64.getUrlEncoder().encodeToString(ByteArray(32){8})
        val id=AndroidDeploySwg.groupId("fixture","qwdtt")
        p.put("call_count",1).put("call_group",id).put("vk_calls",JSONArray().put("https://vk.ru/call/join/$hash"))
        val groups=JSONObject().put(id,JSONObject().put("kind","qwdtt").put("count",1).put("hashes",JSONArray().put(hash)))
        AndroidDeploySwg.validateSaved(array,"fixture",groups)
        groups.getJSONObject(id).put("kind","wdtt")
        assertThrows(IllegalArgumentException::class.java){AndroidDeploySwg.validateSaved(array,"fixture",groups)}
    }
    private fun all()=JSONObject().put("swg_profiles",AndroidDeploySwg.drafts(JSONObject()).apply {repeat(length()){getJSONObject(it).put("enabled",true)}})
    @Test fun legacyDraftKeepsItsProtocolPortsAndCalls() {
        val input=JSONObject().put("relay_kind","wdtt").put("relay_port","57000").put("wg_port","52000").put("vk_calls",JSONArray().put("retained"))
        val p=AndroidDeploySwg.profiles(input).single()
        assertEquals("wdtt",p.getString("kind"));assertEquals(57000,p.getInt("relay_port"));assertEquals("retained",p.getJSONArray("vk_calls").getString(0))
        assertFalse(input.has("swg_profiles"))
    }
    @Test fun allProtocolsHaveIndependentListenersAndNoImplicitSharedCalls() {
        val o=all();assertEquals(listOf(56000,51820,56001,51821,56002,56003),AndroidDeploySwg.ports(o))
        AndroidDeploySwg.profiles(o).forEach {assertEquals(0,it.getJSONArray("vk_calls").length())}
    }
    @Test fun duplicatePortsRejectedAcrossProtocols() {val o=all();o.getJSONArray("swg_profiles").getJSONObject(1).put("wg_port",56000);assertThrows(IllegalArgumentException::class.java){AndroidDeploySwg.profiles(o)}}
    @Test fun emptySelectionRejectedWithoutErasingDrafts() {val o=all();val a=o.getJSONArray("swg_profiles");repeat(a.length()){a.getJSONObject(it).put("enabled",false)};assertThrows(IllegalArgumentException::class.java){AndroidDeploySwg.profiles(o)};assertEquals(4,a.length())}
    @Test fun automaticGroupsCannotCollideAcrossProtocols() {assertEquals(4,AndroidDeploySwg.kinds.map {AndroidDeploySwg.groupId("fixture",it)}.toSet().size)}
    @Test fun missingManualCallsRequireExplicitAutomaticChoice() {assertThrows(IllegalArgumentException::class.java){AndroidDeploySwg.validateCalls(all())};AndroidDeploySwg.validateCalls(all().put("automatic_calls",true))}
    @Test fun duplicateKindsAndUnknownDraftFieldsRejected() {
        val a=AndroidDeploySwg.drafts(JSONObject());a.getJSONObject(1).put("kind","qwdtt");assertThrows(IllegalArgumentException::class.java){AndroidDeploySwg.validateDrafts(a)}
        val b=AndroidDeploySwg.drafts(JSONObject());b.getJSONObject(0).put("command","forbidden");assertThrows(IllegalArgumentException::class.java){AndroidDeploySwg.validateDrafts(b)}
    }
}
