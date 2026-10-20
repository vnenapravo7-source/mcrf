package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Queued with the whole P0 network/settings batch; not a separate test run. */
class GlobalIpv4CompatibilityTest {
    private fun source()=JSONObject("""{
        "outbounds":[{"type":"direct","tag":"direct"}],
        "route":{"final":"direct","rules":[{"action":"resolve"}],"default_domain_resolver":{"server":"chosen","strategy":"prefer_ipv4"}},
        "dns":{"servers":[{"type":"local","tag":"chosen"}],"final":"chosen","strategy":"prefer_ipv4"},
        "inbounds":[{"type":"tun","tag":"tun-in"},{"type":"mixed","tag":"local-proxy"}]
    }""")
    private fun auth()=JSONObject().put("enabled",false).put("username","fixture-user").put("password","fixture-password")
    @Test fun defaultIsOffAndLegacySettingsRemainOff() {
        assertFalse(ClientTunSettings().ipv4Compatibility)
        val legacy=ClientTunSettings().json().apply {remove("ipv4Compatibility")}
        assertFalse(ClientTunSettings.fromJson(legacy).ipv4Compatibility)
        assertTrue(ClientTunSettings.fromJson(ClientTunSettings(ipv4Compatibility=true).json()).ipv4Compatibility)
    }
    @Test fun everyNewRuntimeCopyUsesGlobalPolicyWithoutChangingProfileOrTunProtection() {
        val source=source();val original=source.toString()
        repeat(2) {
            val result=ClientTunConfig.apply(source,ClientTunSettings(ipv4Compatibility=true),auth(),10808).config
            assertEquals("ipv4_only",result.getJSONObject("dns").getString("strategy"))
            assertEquals("ipv4_only",result.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("strategy"))
            assertEquals("ipv4_only",result.getJSONObject("route").getJSONObject("default_domain_resolver").getString("strategy"))
            assertEquals(ClientTunSettings.DEFAULT_ADDRESS6,result.getJSONArray("inbounds").getJSONObject(0).getJSONArray("address").getString(1))
            assertEquals(original,source.toString())
        }
    }
    @Test fun disabledPolicyPreservesDnsAndProxyOnlyModeAlsoUsesThePolicy() {
        assertEquals("prefer_ipv4",ClientTunConfig.apply(source(),ClientTunSettings(),auth(),10808).config.getJSONObject("dns").getString("strategy"))
        val proxy=source().apply {getJSONArray("inbounds").remove(0)}
        val result=ClientTunConfig.apply(proxy,ClientTunSettings(ipv4Compatibility=true),auth(),10808).config
        assertEquals(1,result.getJSONArray("inbounds").length())
        assertEquals("ipv4_only",result.getJSONObject("dns").getString("strategy"))
    }
    @Test(expected=IllegalArgumentException::class)
    fun explicitIpv6OnlyProfileIsNotSilentlyRewrittenToPretendCompatibility() {
        val profile=source().apply {getJSONObject("dns").put("strategy","ipv6_only")}
        ClientTunConfig.apply(profile,ClientTunSettings(ipv4Compatibility=true),auth(),10808)
    }
}
