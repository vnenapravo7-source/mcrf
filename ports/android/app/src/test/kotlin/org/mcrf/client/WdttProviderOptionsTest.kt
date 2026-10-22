package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WdttProviderOptionsTest {
    private fun profile()=JSONObject().put("type","wdtt").put("server","192.0.2.1").put("server_port",56000)
        .put("wg_port",56001).put("local_port",9000).put("password","owned-fixture-password")
        .put("hashes",JSONArray().put("owned-fixture-call-0123456789"))
    @Test fun nativeProviderOptionsSurviveNormalizationAndShareImport() {
        val source=profile().put("vkcalls_preflight",false).put("turn_stream_first",true).put("turn_sni","example.test").put("client_ids","123,456")
        val normalized=SwgProfile.fromJson("fixture",source).carrier!!
        val restored=ServerImport.read(SwgProfile.share(normalized)).single().carrier!!
        assertFalse(restored.getBoolean("vkcalls_preflight"));assertTrue(restored.getBoolean("turn_stream_first"))
        assertEquals("example.test",restored.getString("turn_sni"));assertEquals("123,456",restored.getString("client_ids"))
        val runtime=SwgProfile.privateConfig(restored,22001,22002,"owned-user","owned-pass","owned-device")
        assertEquals("example.test",runtime.getString("turn_sni"));assertTrue(runtime.getBoolean("turn_stream_first"))
    }
    @Test fun oldProfilesKeepFastCallsEnabledAndUdpFirstWithoutCreatingWarpConsent() {
        val value=SwgProfile.fromJson("fixture",profile()).carrier!!
        assertTrue(value.getBoolean("vkcalls_preflight"));assertFalse(value.getBoolean("turn_stream_first"))
        assertEquals("",value.getString("turn_sni"));assertFalse(value.has("rt_masque_accept_tos"))
    }
    @Test fun unsafeHostClientIdsAndNonBooleanSwitchAreRejected() {
        for(bad in listOf(profile().put("turn_sni","https://example.test/path"),profile().put("client_ids","123,https://example.test"),profile().put("vkcalls_preflight","false")))
            assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",bad)}
    }
    @Test fun customLegacyCredentialsArePairedPrivateAndNeverInSharedLink() {
        val value=SwgProfile.fromJson("fixture",profile().put("custom_vk_client_id","12345").put("custom_vk_client_secret","owned-fixture-secret")).carrier!!
        assertEquals("owned-fixture-secret",SwgProfile.privateConfig(value,22001,22002,"user","pass","device").getString("custom_vk_client_secret"))
        val shared=SwgProfile.share(value)
        assertFalse(shared.contains("owned-fixture-secret"));assertFalse(shared.contains("custom_vk_client"))
        for(bad in listOf(profile().put("custom_vk_client_id","123"),profile().put("custom_vk_client_secret","secret"),profile().put("custom_vk_client_id","abc").put("custom_vk_client_secret","secret")))
            assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",bad)}
    }
}
