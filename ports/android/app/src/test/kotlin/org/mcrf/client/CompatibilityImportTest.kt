package org.mcrf.client

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CompatibilityImportTest {
    private val vless = "vless://11111111-1111-4111-8111-111111111111@example.invalid:443?security=tls#Fixture"
    private fun reject(block: () -> Unit) { try { block(); fail("Must reject") } catch (_: IllegalArgumentException) { } }
    private fun wg() = "[Interface]\nPrivateKey = ${Base64.getEncoder().encodeToString(ByteArray(32){1})}\nAddress = 10.0.0.2/32\nDNS = 1.1.1.1, 2606:4700:4700::1111\n[Peer]\nPublicKey = ${Base64.getEncoder().encodeToString(ByteArray(32){2})}\nEndpoint = example.invalid:51820\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25"
    @Test fun encodedJsonIsReclassified() {
        val source = ConnectionEdits.share(ServerImport.read(vless).single(), true)
        val wrapped = Base64.getEncoder().encodeToString(source.toByteArray()).chunked(76).joinToString("\n")
        assertEquals("sing-box", ServerImport.read(wrapped).single().protocol)
    }
    @Test fun encodedIniPreservesDns() {
        val encoded = Base64.getUrlEncoder().encodeToString(wg().toByteArray())
        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), ServerImport.read(encoded).single().profileDns)
    }
    @Test fun nativeJsonArraysAndWrappersRoundTrip() {
        val data = JSONObject().put("type", "dnstt").put("domain", "t.example.invalid").put("key", "ab".repeat(32)).put("udp", "1.1.1.1:53")
        assertEquals("dnstt", ServerImport.read(JSONArray().put(data).toString()).single().protocol)
        assertEquals("dnstt", ServerImport.read(JSONObject().put("profiles", JSONArray().put(data)).toString()).single().protocol)
    }
    @Test fun nestedArraysAndTrailingJsonAreRejected() {
        reject { ServerImport.read("{" + "\"outbounds\":[]}" + " junk") }
        var nested = JSONArray().put(vless).toString(); repeat(10) { nested = "[$nested]" }
        reject { ServerImport.read(nested) }
        reject { ServerImport.read("[".repeat(500) + "0" + "]".repeat(500)) }
    }
    @Test fun opaqueHappNeverFetchesOrLeaks() {
        for (prefix in listOf("crypt", "crypt2", "crypt3", "crypt4", "crypt5", "crypto")) {
            val source = "happ://$prefix/PRIVATE_FIXTURE"
            assertEquals(source, ImportLink.config(source))
            try { ImportPlan.prepare(source) { fail("No fetch"); throw IllegalStateException() }; fail() }
            catch (error: IllegalArgumentException) { assertEquals(HappCompatibility.ENCRYPTED_MESSAGE, error.message); assertFalse(error.message!!.contains("PRIVATE_FIXTURE")) }
        }
    }
    @Test fun cryptoApiRequestRequiresSeparateConsent() {
        reject { HappCompatibility.encryptionRequest("https://example.invalid/private", false) }
        assertEquals("https://example.invalid/private", HappCompatibility.encryptionRequest("https://example.invalid/private", true).getString("url"))
        reject { HappCompatibility.encryptionRequest("http://example.invalid/private", true) }
        try { HappCompatibility.encryptionRequest("https://example.invalid/private secret", true); fail() }
        catch (error: IllegalArgumentException) { assertFalse(error.message!!.contains("private secret")); assertFalse(error.message!!.contains("example.invalid")) }
    }
    @Test fun metadataHeadersOverrideBodyWithoutPolicyExecution() {
        val meta = HappCompatibility.metadata("#subscription-userinfo: upload=4; download=5; total=10; expire=999\n#subscription-always-hwid-enable: 1\n#profile-title: base64:VGVzdA==\n#profile-device-limit: 3", mapOf("Subscription-Userinfo" to "upload=6; download=-1; total=20; expire=1000"))
        assertEquals(6, meta.getLong("upload")); assertFalse(meta.has("download")); assertEquals(1000, meta.getLong("expire")); assertEquals(3, meta.getInt("deviceLimit")); assertEquals("Test", meta.getString("title")); assertTrue(meta.getBoolean("providerRequestsHwid")); assertFalse(meta.has("hwid"))
    }
    @Test fun geoHooksRetainActualXrayDataReferences() {
        assertEquals(setOf("geosite:ru", "ext:custom.dat:ads"), HappCompatibility.geoReferences(JSONObject().put("domain", JSONArray().put("geosite:ru").put("ext:custom.dat:ads").put("example.invalid"))))
    }
    @Test fun encryptedShareRoundTripsAndRejectsTamperAndWrongPassword() {
        val password = "Fixture secret".toCharArray()
        val link = CompatibilityImportEncrypted.encrypt(vless, password)
        assertFalse(link.contains("example.invalid")); assertEquals(vless, CompatibilityImportEncrypted.decrypt(link, password))
        assertEquals(vless, ImportPlan.prepareEncrypted(link, password) { throw AssertionError("No fetch") }.entries.single().original)
        reject { CompatibilityImportEncrypted.decrypt(link, "wrong secret".toCharArray()) }
        val bytes = Base64.getUrlDecoder().decode(link.substring(CompatibilityImportEncrypted.PREFIX.length)); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        reject { CompatibilityImportEncrypted.decrypt(CompatibilityImportEncrypted.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), password) }
    }
    @Test fun editedWireguardKeepsDnsRoutesAndKeysInConf() {
        val entry = ServerImport.read(wg()).single(); val edit = ConnectionEdits.snapshot(entry).put("name", "Edited WG")
        edit.getJSONObject("endpoint").getJSONArray("peers").getJSONObject(0).put("port", 12345)
        val changed = ConnectionEdits.apply(entry, edit)
        val imported = ServerImport.read(ConnectionEdits.share(changed, true)).single()
        assertEquals(12345, changed.port); assertEquals(entry.id, changed.id); assertEquals("Edited WG", imported.name); assertEquals(entry.profileDns, imported.profileDns); assertEquals(12345, imported.port)
    }
    @Test fun invalidWireguardEditsAreRejected() {
        val entry = ServerImport.read(wg()).single(); val edit = ConnectionEdits.snapshot(entry)
        edit.getJSONObject("endpoint").put("private_key", "bad")
        reject { ConnectionEdits.apply(entry, edit) }
    }
    @Test fun carrierEditNormalizesBeforeRuntimeAndShare() {
        val entry = ServerImport.read("dnstt://${"ab".repeat(32)}@t.example.invalid?udp=1.1.1.1%3A53").single()
        val changed = ConnectionEdits.apply(entry, ConnectionEdits.snapshot(entry).put("name", "DNS fixture"))
        assertEquals("DNS fixture", changed.carrier!!.getString("name")); assertEquals("DNS fixture", ServerImport.read(ConnectionEdits.share(changed, true)).single().name)
    }
    @Test fun sip008AndSingleSingboxOutboundPreservePluginAndTls() {
        val sip = JSONObject().put("version", 1).put("servers", JSONArray().put(JSONObject().put("server", "example.invalid").put("server_port", 8388).put("method", "aes-128-gcm").put("password", "fixture").put("remarks", "SIP fixture").put("plugin", "obfs-local").put("plugin_opts", "obfs=http")))
        val entry = ServerImport.read(sip.toString()).single()
        assertEquals("shadowsocks", entry.protocol); assertEquals("obfs=http", entry.outbound!!.getString("plugin_opts")); assertEquals("SIP fixture", entry.name)
        val outbound = ServerImport.read(vless).single().outbound!!
        assertEquals("example.invalid", ServerImport.read(outbound.toString()).single().host)
        assertTrue(ServerImport.read(outbound.toString()).single().outbound!!.getJSONObject("tls").getBoolean("enabled"))
    }
    @Test fun xrayJsonIsReportedHonestly() {
        try { ServerImport.read("{\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"proxy\"}]}"); fail() }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("Xray")) }
    }
    @Test fun allNativeCarriersRetainCanonicalOptionsAfterEditAndShare() {
        for (type in listOf("csqtt", "qwdtt", "wdtt")) {
            val raw = JSONObject().put("type", type).put("server", "example.invalid").put("server_port", 56000).put("password", "synthetic-fixture").put("hashes", JSONArray().put("synthetic_fixture_call_hash"))
                .put("workers", 9).put("captcha_mode", "rjs").put("device_id", "synthetic-installation")
            if (type == "qwdtt") raw.put("mode", "raw").put("raw_port", 56003).put("go_dns", "doh-google")
            val entry = ServerImport.read(raw.toString()).single()
            val changed = ConnectionEdits.apply(entry, ConnectionEdits.snapshot(entry).put("name", "Edited $type"))
            val shared = ServerImport.read(ConnectionEdits.share(changed, true)).single()
            assertEquals(changed.carrier!!.toString(), shared.carrier!!.toString())
        }
        val openflux = JSONObject().put("type", "openflux").put("secret", "synthetic-fixture-secret").put("negotiate", true).put("codec", "batched").put("context", "fixture").put("transports", JSONArray().put(JSONObject().put("type", "direct").put("dial", "example.invalid:443")))
        val open = ServerImport.read(openflux.toString()).single()
        val changed = ConnectionEdits.apply(open, ConnectionEdits.snapshot(open).put("name", "Edited flux"))
        assertEquals(changed.carrier!!.toString(), ServerImport.read(ConnectionEdits.share(changed, true)).single().carrier!!.toString())
        val olc = ServerImport.read("olcrtc://jitsi?videochannel<video-w=1080&video-h=1080&video-codec=tile>@meet.example.invalid/room#${"ab".repeat(32)}").single()
        val edited = ConnectionEdits.apply(olc, ConnectionEdits.snapshot(olc).put("name", "Edited OLC"))
        assertEquals(edited.carrier!!.toString(), ServerImport.read(ConnectionEdits.share(edited, true)).single().carrier!!.toString())
    }
    @Test fun encodedMetadataKeepsQuotaAndRejectsMalformedUtf8Title() {
        val body = "#subscription-userinfo: upload=7; download=9; total=30; expire=1000\n#profile-title: base64:/w==\n$vless"
        val meta = HappCompatibility.metadata(Base64.getUrlEncoder().encodeToString(body.toByteArray()).chunked(76).joinToString("\n"))
        assertEquals(7, meta.getLong("upload")); assertEquals(9, meta.getLong("download")); assertFalse(meta.has("title"))
        reject { ServerImport.read(Base64.getEncoder().encodeToString(byteArrayOf(0xff.toByte()))) }
    }
    @Test fun dnsttRejectsInvalidResolverAndUtf8CredentialsBeforeRuntime() {
        fun raw() = JSONObject().put("type", "dnstt").put("domain", "t.example.invalid").put("key", "ab".repeat(32))
        assertEquals("https://cloudflare-dns.com/dns-query", ServerImport.read(raw().toString()).single().carrier!!.getString("doh"))
        reject { CarrierProfile.fromJson("fixture", raw().put("doh", "")) }
        reject { CarrierProfile.fromJson("fixture", raw().put("doh", "https://example.invalid:0/dns-query")) }
        reject { CarrierProfile.fromJson("fixture", raw().put("username", "я".repeat(128)).put("password", "synthetic")) }
        reject { CarrierProfile.fromJson("fixture", raw().put("username", "fixture\r").put("password", "synthetic")) }
        assertEquals("fixture", CarrierProfile.fromJson("fixture", raw().put("username", "fixture").put("password", "я".repeat(127))).carrier!!.getString("username"))
    }
    @Test fun encryptedCataloguePayloadRestoresAllCarrierEditsAndWgDns() {
        val catalogue = ConnectionCatalogue("")
        val inputs = mutableListOf(wg())
        inputs.add(wg().replace("[Interface]", "[Interface]\nJc = 3\nJmin = 40\nJmax = 90\nH1 = 1-1000"))
        for (type in listOf("csqtt", "qwdtt", "wdtt")) inputs.add(JSONObject().put("type", type).put("server", "example.invalid").put("server_port", 56000).put("password", "synthetic-fixture").put("hashes", JSONArray().put("synthetic_fixture_call_hash")).put("workers", 9).toString())
        inputs.add("dnstt://${"ab".repeat(32)}@t.example.invalid?udp=1.1.1.1%3A53&username=fixture&password=fixture-pass")
        inputs.add("olcrtc://jitsi?datachannel@meet.example.invalid/room#${"ab".repeat(32)}")
        inputs.add(JSONObject().put("type", "openflux").put("secret", "synthetic-fixture-secret").put("negotiate", true).put("transports", JSONArray().put(JSONObject().put("type", "direct").put("dial", "example.invalid:443"))).toString())
        inputs.forEach(catalogue::add)
        catalogue.entries().forEach { entry -> catalogue.edit(entry.id, ConnectionEdits.snapshot(entry).put("name", "Edited ${entry.protocol}")) }
        val plain = catalogue.serialize().toByteArray(); val password = "Fixture passphrase".toCharArray()
        val encrypted = BackupCodec.encrypt(plain, password)
        val restored = ConnectionCatalogue(String(BackupCodec.decrypt(encrypted, password), Charsets.UTF_8))
        assertEquals(catalogue.serialize(), restored.serialize())
        assertEquals(8, restored.entries().size)
        restored.entries().forEach { entry -> assertTrue(restored.edited(entry.id)); if (entry.endpoint != null) assertEquals(2, entry.profileDns.size) }
    }
}
