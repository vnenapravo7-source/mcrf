package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Reproduces the OFF state without VK, profile writes, login or cache reset. */
class VkSupplementIsolationTest {
    private class Memory:VkRelayPersistence {
        val root=JSONObject()
        override fun get(section:String)=JSONObject(root.optJSONObject(section)?.toString()?:"{}")
        override fun update(section:String,change:(JSONObject)->Unit){val value=get(section);change(value);root.put(section,value)}
        override fun remove(section:String){error("No reset/logout allowed")}
    }
    @Test fun disabledLibraryOf24HashesNeverChangesAnySavedCsqttVersionOrMode() {
        val memory=Memory()
        val account=VkRelayAccount(memory,{error("No implicit VK calls")},{error("No delays")})
        account.setManualDefaults((0 until 24).map {java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){it.toByte()}.apply {this[0]=it.toByte()})})
        account.setSupplementAll(false)
        val state=memory.root.toString()
        for(implementation in listOf("swg-panel-2.5.1","swg-panel-2.5.2","swg-panel-2.5.3")) {
            val raw=JSONObject().put("type","csqtt").put("csqtt_generation","2.5")
                .put("csqtt_implementation",implementation).put("vk_hash_mode","manual")
                .put("hashes",JSONArray().put("A".repeat(43))).put("workers",10)
                .put("name","fixture").put("server","fixture.invalid").put("server_port",56000).put("password","fixture")
            assertPassThrough(raw,account,true)
            val automatic=JSONObject(raw.toString()).put("hashes",JSONArray()).put("vk_hash_mode","auto_js").put("vk_token","fixture-token")
            assertPassThrough(automatic,account,true)
        }
        for(generation in listOf("legacy","2.1.5","2.0")) {
            val raw=JSONObject().put("type","csqtt").put("csqtt_generation",generation)
                .put("hashes",JSONArray().put("A".repeat(43))).put("workers",18)
                .put("name","fixture").put("server","fixture.invalid").put("server_port",56000).put("password","fixture")
            assertPassThrough(raw,account,false)
        }
        assertEquals(state,memory.root.toString())
        assertEquals(24,account.manualDefaults().size)
    }
    private fun assertPassThrough(raw:JSONObject,account:VkRelayAccount,modern:Boolean) {
        val original=raw.toString()
        val copy=VkRelayRuntime.withManualSupplements(raw,account)
        assertEquals(original,copy.toString())
        val normalized=SwgProfile.fromJson("fixture",copy).carrier!!
        if(modern) {
            val config=Csqtt25Profile.privateConfig(normalized,21001,21002,"fixture-user","fixture-pass","fixture-id")
            assertEquals(raw.getString("vk_hash_mode"),config.getString("vk_hash_mode"))
            assertEquals(raw.getJSONArray("hashes").toString(),config.getJSONArray("hashes").toString())
        }else {
            val prepared=VkRelayRuntime.prepare(copy,account)
            SwgProfile.privateConfig(prepared,21001,21002,"fixture-user","fixture-pass","fixture-id")
        }
        assertEquals(original,raw.toString())
    }
    @Test fun togglingOffRestoresProfileImmediatelyWithTheSameAccountObject() {
        val account=VkRelayAccount(Memory(),{error("No VK request")},{})
        account.setManualDefaults(listOf("B".repeat(43)))
        val raw=JSONObject().put("type","csqtt").put("hashes",JSONArray().put("A".repeat(43)))
        val original=raw.toString()
        account.setSupplementAll(true)
        assertEquals(2,VkRelayRuntime.withManualSupplements(raw,account).getJSONArray("hashes").length())
        account.setSupplementAll(false)
        assertEquals(original,VkRelayRuntime.withManualSupplements(raw,account).toString())
        assertEquals(original,raw.toString())
        assertEquals(1,account.manualDefaults().size)
    }
    @Test fun explicitSubnetRepairPreservesManualSettingsAndGlobalFlagOff() {
        val memory=Memory();val account=VkRelayAccount(memory,{error("No implicit VK calls")},{})
        account.setManualDefaults(listOf("A".repeat(43)));account.setSupplementAll(false)
        memory.update("vkSettings") {it.put(VkTurnSubnets.SELECTED,"untrusted-value").put(VkTurnSubnets.IP_LIMIT,"bad").put(VkTurnSubnets.CUSTOM_IPS,"bad")}
        val before=memory.root.toString()
        assertThrows(IllegalArgumentException::class.java){account.turnSubnets()}
        assertEquals(before,memory.root.toString())
        account.setTurnSubnets(VkTurnSubnets.Selection(VkTurnSubnets.pools.first().id,"2"))
        assertEquals(1,account.manualDefaults().size);assertFalse(account.supplementAll())
        AndroidDeployBackupSettings.validate(memory.root)
        val raw=JSONObject().put("type","csqtt").put("csqtt_generation","2.5").put("hashes",JSONArray().put("A".repeat(43)))
        val original=raw.toString()
        val effective=VkRelayRuntime.withManualSupplements(raw,account)
        assertEquals(1,effective.getJSONArray("hashes").length())
        assertEquals(2,effective.getJSONArray("custom_turn_ips").length())
        assertEquals(original,raw.toString())
        val explicit=JSONObject(raw.toString()).put("custom_turn_ips",JSONArray().put("192.0.2.1"))
        assertEquals(explicit.toString(),VkRelayRuntime.withManualSupplements(explicit,account).toString())
    }
}
