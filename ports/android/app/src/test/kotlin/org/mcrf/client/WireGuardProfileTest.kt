package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WireGuardProfileTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private fun conf(extra: String = "") = "[Interface]\nName = Тест WG\nPrivateKey = $key\nAddress = 10.77.0.2, fd77::2/128\nDNS = 10.77.0.1, fd77::1\nMTU = 1280\n$extra\n[Peer]\nPublicKey = $key\nPresharedKey = $key\nEndpoint = [2001:db8::1]:51820\nAllowedIPs = 10.77.0.0/24, fd77::/64\nPersistentKeepalive = 25\nReserved = 1, 2, 255\n"
    private fun rejects(source: String) { assertThrows(Exception::class.java) { WireGuardProfile.parse(source) } }
    @Test fun confAndLinkRoundTripAllFields() {
        val profile = WireGuardProfile.parse(conf())
        assertEquals(profile.fields(), WireGuardProfile.parse(profile.conf()).fields())
        assertEquals(profile.fields(), WireGuardProfile.parse(profile.link()).fields())
        assertEquals(listOf("10.77.0.1", "fd77::1"), profile.dns())
        assertEquals("fd77::2/128", profile.endpoint().getJSONArray("address").getString(1))
        assertEquals("10.77.0.0/24", profile.endpoint().getJSONArray("peers").getJSONObject(0).getJSONArray("allowed_ips").getString(0))
        assertEquals(profile.fields(), WireGuardProfile.fromEndpoint(profile.endpoint(), "Тест WG", profile.dns()).fields())
    }
    @Test fun qThroneLinkAliasesAndLiteralPlus() {
        val source = "wg://[2001:db8::1]:51820?private_key=$key&public_key=$key&local_address=10.1.1.2/32-fd77::2/128&h1=123#Fixture"
        val profile = WireGuardProfile.parse(source)
        assertEquals(WireGuardProfile.Version.AWG_1, profile.version)
        assertEquals("10.1.1.2/32, fd77::2/128", profile.fields()["Address"])
    }
    @Test fun rejectsMalformedAndAmbiguousImports() {
        listOf(conf().replace("Address =", "Address = x\nAddress ="), conf()+"[Peer]\nPublicKey = $key", conf().replace("MTU = 1280", "PostUp = echo unsafe"), conf().replace("[Interface]", "[Unknown]"), conf().replace("10.77.0.2", "999.1.1.1"), conf().replace("fd77::2/128", "fd77::2/129"), conf().replace("51820", "0"), conf().replace("PersistentKeepalive = 25", "PersistentKeepalive = -1"), conf().replace("1, 2, 255", "1, 2, 256"), conf().replace("MTU = 1280", "MTU = 50"), conf().replace("DNS = 10.77.0.1, fd77::1", "DNS = search.example"), conf().replace("PrivateKey = $key", "PrivateKey = short")).forEach(::rejects)
        rejects(WireGuardProfile.parse(conf()).link().replace("Address=", "Unknown="))
        rejects(WireGuardProfile.parse(conf()).link().replace("#", "&MTU=1280#"))
        rejects(WireGuardProfile.parse(conf()).link().replace("wireguard:", "awg:"))
    }
    @Test fun awgVersionsAndNativeFieldMapping() {
        val awg1 = "Jc = 4\nJmin = 10\nJmax = 30\nS1 = 16\nS2 = 20\nH1 = 100\nH2 = 200\nH3 = 300\nH4 = 400"
        val awg2 = "$awg1\nS3 = 12\nS4 = 12\nI1 = <b 0x0102><r 8>"
        val awg3 = "$awg2\nHeaderProtectionKey = $key\nContentPaddingAddition = 1-10"
        val awg31 = "$awg3\nRandomTrailers = yes\nDisableCookies = off\nRekeyAfterTime = 60-120\nRekeyTimeout = 5\nRejectAfterTime = 180\nKeepaliveTimeout = 10\nMaxHandshakeAttempts = 5"
        listOf(awg1 to WireGuardProfile.Version.AWG_1, awg2 to WireGuardProfile.Version.AWG_2, awg3 to WireGuardProfile.Version.AWG_3, awg31 to WireGuardProfile.Version.AWG_3_1).forEach { (extra,version) ->
            val profile = WireGuardProfile.parse(conf(extra))
            assertEquals(version, profile.version)
            assertEquals(profile.fields(), WireGuardProfile.parse(profile.link()).fields())
            assertEquals(profile.fields(), WireGuardProfile.fromEndpoint(profile.endpoint(), "Тест WG", profile.dns()).fields())
            assertFalse(profile.endpoint().has("amnezia_wg"))
        }
        assertEquals(false, WireGuardProfile.parse(conf(awg31)).endpoint().getBoolean("disable_cookies"))
    }
    @Test fun rejectsInvalidObfuscationAndPadding() {
        listOf("Jmin = 60\nJmax = 10", "Jc = -1", "H1 = 4294967296", "H1 = 20-10", "H1 = 1-2-3", "RandomTrailers = maybe", "I1 = not-cps", "HeaderProtectionKey = $key\nS1 = 12\nS2 = 12\nS3 = 12\nS4 = 11", "HeaderProtectionKey = 0123456789abcdef0123456789abcdef").forEach { rejects(conf(it)) }
    }
    @Test fun editedProfilePreservesUnchangedSecretsAndRoutes() {
        val profile = WireGuardProfile.parse(conf("I1 = <r 8>\nH1 = 100-200"))
        val edited = profile.edited(mapOf("Endpoint" to "example.org:2408", "DNS" to null))
        assertEquals(profile.fields()["PrivateKey"], edited.fields()["PrivateKey"])
        assertEquals(profile.fields()["AllowedIPs"], edited.fields()["AllowedIPs"])
        assertEquals(profile.fields()["I1"], edited.fields()["I1"])
        assertTrue(edited.dns().isEmpty())
        assertFalse(edited.toString().contains(key))
        assertThrows(Exception::class.java) { profile.edited(mapOf("Name" to "x\nPostUp = unsafe")) }
    }
    @Test fun endpointRejectsLossyUnknownFields() {
        val node = WireGuardProfile.parse(conf()).endpoint().put("unexpected", true)
        assertThrows(Exception::class.java) { WireGuardProfile.fromEndpoint(node) }
        assertThrows(Exception::class.java) { WireGuardProfile.validateEndpoint(JSONObject("{\"type\":\"wireguard\",\"peers\":[]}")) }
    }
    @Test fun rangedKeepaliveAndListenPort() {
        val profile = WireGuardProfile.parse(conf("ListenPort = 51821").replace("PersistentKeepalive = 25", "PersistentKeepalive = 20-30"))
        assertEquals(WireGuardProfile.Version.AWG_3, profile.version)
        assertEquals(51821, profile.endpoint().getInt("listen_port"))
        assertEquals("20-30", profile.endpoint().getJSONArray("peers").getJSONObject(0).getString("persistent_keepalive_interval"))
        assertEquals(profile.fields(), WireGuardProfile.parse(profile.link()).fields())
    }
    @Test fun workerTimeoutAndSystemInterfaceValidation() {
        val profile = WireGuardProfile.parse(conf("Workers = 2\nUDPTimeout = 1m30s\nSystem = false"))
        assertEquals(2,profile.endpoint().getInt("workers"))
        assertEquals("1m30s",profile.endpoint().getString("udp_timeout"))
        assertEquals(profile.fields(),WireGuardProfile.parse(profile.link()).fields())
        assertEquals(profile.fields(),WireGuardProfile.fromEndpoint(profile.endpoint(),"Тест WG",profile.dns()).fields())
        rejects(conf("System = true"));rejects(conf("Workers = 300"));rejects(conf("UDPTimeout = -1s"))
        val link="wg://[2001:db8::1]:51820?private_key=$key&public_key=$key&address=10.77.0.2&enable_amnezia=true&h1=123&workers=2&use_system_interface=false"
        assertEquals(WireGuardProfile.Version.AWG_1,WireGuardProfile.parse(link).version)
    }
}
