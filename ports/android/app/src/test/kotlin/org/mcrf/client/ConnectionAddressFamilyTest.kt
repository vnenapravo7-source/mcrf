package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionAddressFamilyTest {
    private fun config() = JSONObject("""{"dns":{"servers":[{"type":"local","tag":"system"}],"strategy":"prefer_ipv4"},"route":{"rules":[{"action":"resolve","strategy":"prefer_ipv4"},{"ip_cidr":["::/0"],"outbound":"proxy"}]},"inbounds":[{"type":"tun","address":["172.19.0.1/30","fd00::1/126"],"strict_route":true}]}""")
    @Test fun defaultDoesNotAlterAnyConnection() {
        val source=config();assertSame(source,ConnectionAddressFamily.apply(source,false))
    }
    @Test fun explicitCompatibilityCopiesOnlyDnsPolicyAndKeepsIpv6Barrier() {
        val source=config();val before=source.toString();val changed=ConnectionAddressFamily.apply(source,true)
        assertEquals(before,source.toString());assertNotSame(source,changed)
        assertEquals("ipv4_only",changed.getJSONObject("dns").getString("strategy"))
        assertEquals("ipv4_only",changed.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("strategy"))
        assertEquals(source.getJSONArray("inbounds").toString(),changed.getJSONArray("inbounds").toString())
        assertEquals(source.getJSONObject("route").getJSONArray("rules").getJSONObject(1).toString(),changed.getJSONObject("route").getJSONArray("rules").getJSONObject(1).toString())
    }
    @Test fun conflictingExplicitIpv6IsNotSilentlyOverridden() {
        val source=config();source.getJSONObject("dns").put("strategy","ipv6_only");val before=source.toString()
        try {ConnectionAddressFamily.apply(source,true);fail("Explicit IPv6 conflict must be reported")} catch (_:IllegalArgumentException) {}
        assertEquals(before,source.toString())
    }
    @Test fun preferenceIsBoundToOneValidatedCatalogueIdentity() {
        assertNotEquals(ConnectionAddressFamily.key("a".repeat(64)),ConnectionAddressFamily.key("b".repeat(64)))
        try {ConnectionAddressFamily.key("other-profile");fail("Unvalidated key")} catch (_:IllegalArgumentException) {}
    }
}
