package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CsqttAccountOptionsTest {
    private class Memory : VkRelayPersistence {
        private val sections=mutableMapOf<String,JSONObject>()
        override fun get(section:String)=JSONObject(sections[section]?.toString()?:"{}")
        override fun update(section:String,change:(JSONObject)->Unit) {val value=get(section);change(value);sections[section]=value}
        override fun remove(section:String) {sections.remove(section)}
    }
    private fun profile()=JSONObject().put("type","csqtt").put("server","192.0.2.1").put("server_port",46000)
        .put("password","fixture-password").put("hashes",JSONArray()).put("vk_anon_path","auto_js").put("workers",126)
    @Test fun existingLoginIsPrivateAndSurvivesNeitherNormalizationNorShare() {
        var requests=0
        val account=VkRelayAccount(Memory(),{requests++;error("No host VK requests expected")},{})
        account.link(VkRelayProtocol.Authorization("owned-fixture-token","42",3600))
        val saved=SwgProfile.fromJson("fixture",profile()).carrier!!
        val before=saved.toString()
        val runtime=VkRelayRuntime.prepare(saved,account)
        val config=SwgProfile.privateConfig(runtime,22001,22002,"user","pass","device")
        assertEquals("owned-fixture-token",config.getString("vk_runtime_token"))
        assertEquals("auto_js",config.getString("vk_hash_mode"));assertEquals(0,config.getJSONArray("hashes").length())
        assertEquals(126,config.getInt("workers"));assertEquals(0,requests);assertEquals(before,saved.toString())
        assertFalse(SwgProfile.fromJson("runtime-copy",runtime).carrier!!.has("vk_runtime_token"))
        val share=SwgProfile.share(runtime)
        assertFalse(share.contains("owned-fixture-token"));assertFalse(share.contains("vk_runtime_token"))
        assertEquals("auto_js",ServerImport.read(share).single().carrier!!.getString("vk_anon_path"))
        account.unlink()
        assertThrows(IllegalStateException::class.java){VkRelayRuntime.prepare(saved,account)}
    }
    @Test fun probeNeverUsesAccountOrStartsCallsAndManualModeNeverCopiesToken() {
        val account=VkRelayAccount(Memory(),{error("Probe must not call VK")},{})
        assertThrows(IllegalArgumentException::class.java){VkRelayRuntime.prepareForProbe(profile(),account)}
        val manual=profile().put("vk_anon_path","vkcalls").put("workers",18)
            .put("hashes",JSONArray().put("A".repeat(43))).put("vk_runtime_token","untrusted-profile-token")
        val clean=SwgProfile.fromJson("fixture",manual).carrier!!
        assertFalse(clean.has("vk_runtime_token"));assertEquals("manual",clean.getString("vk_hash_mode"))
        assertFalse(SwgProfile.privateConfig(clean,22001,22002,"user","pass","device").has("vk_runtime_token"))
    }
    @Test fun extraStreamLimitPreservesExistingProfilesAndSharesOnlyTheSwitch() {
        val old=SwgProfile.fromJson("fixture",profile()).carrier!!
        assertTrue(old.getBoolean("extra_streams")) // Existing >72 settings are not silently reduced.
        val restored=ServerImport.read(SwgProfile.share(old)).single().carrier!!
        assertTrue(restored.getBoolean("extra_streams"));assertEquals(126,restored.getInt("workers"))
        assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",profile().put("extra_streams",false))}
        val ordinary=SwgProfile.fromJson("fixture",profile().put("workers",72).put("extra_streams",false)).carrier!!
        assertFalse(ordinary.getBoolean("extra_streams"));assertEquals("chrome",ordinary.getString("fingerprint"))
        assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",profile().put("extra_streams","yes"))}
        assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",profile().put("vk_hash_mode","manual"))}
    }
}
