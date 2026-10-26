package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DnsEndpointReviewTest {
    private fun build(protocol: String, address: String) = DnsConfig.build(
        JSONObject().put("provider", "Свой").put("protocol", protocol).put("custom", address), "review", "bootstrap")

    @Test fun explicitZeroPortIsNotSilentlyReplacedByDefault() {
        for ((protocol, address) in listOf("UDP" to "udp://192.0.2.1:0", "DoT" to "tls://dns.invalid:0", "DoH" to "https://dns.invalid:0/dns-query")) {
            try { build(protocol, address); fail("Explicit zero port must reject") }
            catch (error: IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("dns.invalid")) }
        }
    }
    @Test fun absentPortStillUsesProtocolDefault() {
        assertEquals(53, build("UDP", "192.0.2.1").getInt("server_port"))
        assertEquals(443, build("DoH", "https://dns.invalid/dns-query").getInt("server_port"))
        assertEquals(853, build("DoT", "tls://dns.invalid").getInt("server_port"))
    }
    @Test fun explicitBoundaryPortsArePreserved() {
        assertEquals(1, build("UDP", "udp://192.0.2.1:1").getInt("server_port"))
        assertEquals(65535, build("DoH", "https://dns.invalid:65535/dns-query").getInt("server_port"))
    }
    @Test fun overflowingPortRejectsInsteadOfUsingDefault() {
        try { build("DoH", "https://dns.invalid:65536/dns-query"); fail("Must reject") }
        catch (_: IllegalArgumentException) { }
    }
}
