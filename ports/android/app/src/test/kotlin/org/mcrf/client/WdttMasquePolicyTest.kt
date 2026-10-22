package org.mcrf.client

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WdttMasquePolicyTest {
    private fun profile()=JSONObject().put("type","wdtt").put("server","192.0.2.1").put("server_port",56000)
        .put("password","fixture-password").put("hashes",JSONArray().put("A".repeat(43)))
    @Test fun explicitOptionRequiresSniAndDoesNotImportConsentOrAccount() {
        assertFalse(SwgProfile.fromJson("fixture",profile()).carrier!!.getBoolean("rt_masque"))
        assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",profile().put("rt_masque",true))}
        val value=SwgProfile.fromJson("fixture",profile().put("rt_masque",true).put("turn_sni","example.test")
            .put("accept_tos",true).put("masque_account",JSONObject().put("access_token","fixture-private-token"))).carrier!!
        assertTrue(value.getBoolean("rt_masque"));assertFalse(value.has("accept_tos"));assertFalse(value.has("masque_account"))
        val link=SwgProfile.share(value)
        assertFalse(link.contains("fixture-private-token"));assertFalse(link.contains("accept_tos"))
        assertTrue(ServerImport.read(link).single().carrier!!.getBoolean("rt_masque"))
        assertEquals("org.mcrf.engines.wdtt_masque",CarrierOptionContract.required(value))
    }
    @Test fun preflightFindsOnlyExplicitlyEnabledWdttProfilesAcrossRulesAndBsPool() {
        val enabled=profile().put("rt_masque",true).put("turn_sni","example.test")
        val template=JSONObject().put("mcrf_bs",JSONObject().put("candidates",JSONArray().put(JSONObject().put("profile",enabled))))
        val list=WdttMasqueRegistration.profiles(template,listOf(profile(),JSONObject().put("type","csqtt").put("rt_masque",true),enabled))
        assertEquals(2,list.size);assertTrue(list.all {it.getString("type")=="wdtt"})
    }
}
