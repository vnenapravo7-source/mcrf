package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CompactWireGuardPlanTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private fun profile(extra: String = "", endpoint: String = "example.org:51820") = WireGuardProfile.parse(
        "[Interface]\nPrivateKey=$key\nAddress=10.8.0.2/32,fd08::2/128\nDNS=10.8.0.1\n$extra\n[Peer]\nPublicKey=$key\nEndpoint=$endpoint\nAllowedIPs=0.0.0.0/0,::/0\nPersistentKeepalive=25")

    @Test fun preservesTunDnsRoutesAndRedactsSecrets() {
        val plan = CompactWireGuardPlan.prepare(profile(), "192.0.2.1")
        assertEquals(1420, plan.mtu)
        assertEquals(listOf("10.8.0.2/32", "fd08::2/128"), plan.addresses)
        assertEquals(listOf("0.0.0.0/0", "::/0"), plan.routes)
        assertEquals(listOf("10.8.0.1"), plan.dns)
        assertTrue(plan.secretConfiguration().contains("endpoint=192.0.2.1:51820\n"))
        assertFalse(plan.toString().contains("010203"))
        assertFalse(plan.toString().contains(key))
        assertThrows(UnsupportedOperationException::class.java) { (plan.routes as MutableList).clear() }
    }

    @Test fun ordersAllAwgDeviceFieldsBeforePeerAndConvertsKey() {
        val p = profile("S1=12\nS2=12\nS3=12\nS4=12\nHeaderProtectionKey=$key\nRandomTrailers=true\nH1=100-200\nI1=<r 8>")
        val text = CompactWireGuardPlan.prepare(p, "2001:db8::1").secretConfiguration()
        assertTrue(text.contains("header_protection_key=0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20\n"))
        assertTrue(text.indexOf("random_trailers=true") < text.indexOf("public_key="))
        assertTrue(text.contains("endpoint=[2001:db8::1]:51820\n"))
        assertFalse(text.contains("protocol_version=3"))
    }

    @Test fun refusesLossyUnsupportedFieldsAndSmallMtu() {
        listOf("Workers=1", "UDPTimeout=30s", "MTU=576").forEach { extra ->
            assertThrows(IllegalArgumentException::class.java) { CompactWireGuardPlan.prepare(profile(extra), "192.0.2.1") }
        }
        val reserved = WireGuardProfile.parse(profile().conf() + "\nReserved=1,2,3")
        assertThrows(IllegalArgumentException::class.java) { CompactWireGuardPlan.prepare(reserved, "192.0.2.1") }
    }

    @Test fun awgThreeAndThreeOneKeepExactDeviceParametersBeforePeer() {
        val common = "S1=16\nS2=20\nS3=16\nS4=16\nHeaderProtectionKey=$key\nContentPaddingAddition=0-8"
        val cases = listOf(
            common to WireGuardProfile.Version.AWG_3,
            "$common\nRandomTrailers=true\nDisableCookies=true\nRekeyAfterTime=120\nRekeyTimeout=5\nRejectAfterTime=180\nKeepaliveTimeout=10\nMaxHandshakeAttempts=5" to WireGuardProfile.Version.AWG_3_1
        )
        cases.forEach { (extra, version) ->
            val parsed = profile(extra)
            val original = parsed.conf()
            val plan = CompactWireGuardPlan.prepare(parsed, "2001:db8::123")
            val text = plan.secretConfiguration()
            assertEquals(version, parsed.version)
            assertEquals(original, parsed.conf())
            assertTrue(text.contains("content_padding_addition=0-8\n"))
            assertTrue(text.indexOf("header_protection_key=") < text.indexOf("replace_peers=true"))
            assertTrue(text.contains("endpoint=[2001:db8::123]:51820\n"))
            if (version == WireGuardProfile.Version.AWG_3_1) {
                listOf("random_trailers=true", "disable_cookies=true", "rekey_after_time=120",
                    "rekey_timeout=5", "reject_after_time=180", "keepalive_timeout=10",
                    "max_handshake_attempts=5").forEach { field ->
                    assertTrue(text.contains("$field\n"))
                    assertTrue(text.indexOf(field) < text.indexOf("replace_peers=true"))
                }
            }
            assertFalse(plan.toString().contains(key))
            assertFalse(plan.toString().contains("0102030405060708"))
        }
    }

    @Test fun rejectsDnsFallbackInjectionAndLiteralEndpointSubstitution() {
        listOf("example.org", "dead", "127.1", "192.0.2.256", "192.0.2.1\nlisten_port=1", "fe80::1%wlan0").forEach {
            assertThrows(IllegalArgumentException::class.java) { CompactWireGuardPlan.prepare(profile(), it) }
        }
        assertThrows(IllegalArgumentException::class.java) { CompactWireGuardPlan.prepare(profile(endpoint="192.0.2.1:51820"), "192.0.2.2") }
        assertTrue(CompactWireGuardPlan.prepare(profile(endpoint="[2001:db8::1]:51820"), "2001:db8:0:0:0:0:0:1").secretConfiguration().contains("endpoint="))
    }

    @Test fun missingOptionalRoutesAndDnsAreExplicitAndOriginalIsUnchanged() {
        val input = profile().conf().replace("AllowedIPs = 0.0.0.0/0, ::/0\n", "").replace("DNS = 10.8.0.1\n", "")
        val original = WireGuardProfile.parse(input)
        val before = original.conf()
        val plan = CompactWireGuardPlan.prepare(original, "192.0.2.1")
        assertEquals(listOf("0.0.0.0/0", "::/0"), plan.routes)
        assertTrue(plan.dns.isEmpty())
        assertEquals(before, original.conf())
        assertFalse(plan.secretConfiguration().contains("example.org"))
    }
}
