package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class OpenFluxProfileTest {
    private fun profile() = JSONObject().put("type", "openflux").put("name", "Шифрованный + OpenFlux")
        .put("negotiate", true).put("secret", "a+b/😀 secret with space").put("codec", "batched")
        .put("context", "https://docs.example/fixture")
        .put("transports", JSONArray().put(JSONObject().put("name", "direct-eu").put("type", "direct").put("priority", 100).put("dial", "[2001:db8::1]:443")))
    @Test fun standardLinkKeepsKeyContextAndTransportIdentity() {
        val link = OpenFluxProfile.share(profile()); val entry = ServerImport.read(link).single()
        assertEquals("openflux", entry.protocol); assertEquals("Шифрованный + OpenFlux", entry.name)
        assertEquals("a+b/😀 secret with space", entry.carrier!!.getString("secret"))
        assertEquals("https://docs.example/fixture", entry.carrier.getString("context"))
        assertEquals("direct-eu", entry.carrier.getJSONArray("transports").getJSONObject(0).getString("name"))
        assertEquals("2001:db8::1", entry.host); assertEquals(443, entry.port)
    }
    @Test fun everyOfficialShareTransportIsAccepted() {
        val p = profile(); val list = p.getJSONArray("transports")
        listOf("yandex", "vyandex", "boards", "mailru", "cupsonline").forEach { list.put(JSONObject().put("type", it).put("url", "https://docs.example/$it")) }
        assertEquals(6, ServerImport.read(OpenFluxProfile.share(p)).single().carrier!!.getJSONArray("transports").length())
    }
    @Test fun streamModeIsNotMisreadAsEncryptedSession() {
        val p = JSONObject().put("type", "openflux").put("mode", "stream").put("transports", JSONArray().put(JSONObject().put("type", "mailru").put("url", "https://docs.example/php")))
        assertEquals("stream", ServerImport.read(OpenFluxProfile.share(p)).single().carrier!!.getString("mode"))
        try { OpenFluxProfile.share(p.put("secret", "a sufficiently long key")); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun generatedBridgeKeepsSecretOutOfProxyCredentials() {
        val entry = ServerImport.read(OpenFluxProfile.share(profile())).single()
        val config = ServerImport.build(listOf(entry), entry.id, false, 60, 50, "Cloudflare")
        val socks = config.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("socks", socks.getString("type")); assertFalse(socks.has("password"))
        assertEquals(entry.carrier!!.getString("secret"), config.getJSONObject("mcrf_carrier").getString("secret"))
    }
    @Test fun invalidOrDamagedLinksNeverRevealSecret() {
        val invalid = profile().put("secret", "private-invalid-secret").put("transports", JSONArray().put(JSONObject().put("type", "not-real")))
        try { ServerImport.read(invalid.toString()); fail() } catch (e: IllegalArgumentException) { assertFalse(e.message!!.contains("private-invalid-secret")) }
        for (link in listOf("openflux://v1/invalid==", "openflux://v2/invalid", "OpenFlux://v1/invalid")) {
            try { ServerImport.read(link); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun compressedPayloadIsBounded() {
        val p = profile().put("context", "x".repeat(20000))
        try { OpenFluxProfile.share(p); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun editedShareUsesTheStandardLinkAndCurrentEndpoint() {
        val entry = ServerImport.read(OpenFluxProfile.share(profile())).single()
        val changed = JSONObject(entry.carrier!!.toString()); changed.getJSONArray("transports").getJSONObject(0).put("dial", "new.example:8443")
        val edited = ConnectionEdits.apply(entry, JSONObject().put("name", "Edited").put("carrier", changed))
        val link = ConnectionEdits.share(edited, true); assertTrue(link.startsWith("openflux://v1/"))
        val imported = ServerImport.read(link).single(); assertEquals("Edited", imported.name); assertEquals("new.example", imported.host); assertEquals(8443, imported.port)
    }
}
