package org.mcrf.client

import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CompatibilityImportContractsTest {
    private val url = URI("https://example.invalid/sub?key=key02")
    private val link = "vless://11111111-1111-4111-8111-111111111111@example.invalid:443?security=tls&type=ws&path=%2Ffixture%2Ba#Fixture"
    private fun reject(action: () -> Unit) { try { action(); fail() } catch (_: IllegalArgumentException) { } }
    private fun encrypted(): Pair<ByteArray, String> {
        val key = HappSubscriptionBody.publicTestKeysFor(url).keyFor(url, "key02")!!
        val out = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, "kkkkkkkkkkkk".toByteArray())) }.doFinal(link.toByteArray())
        return Base64.getEncoder().encode(out.copyOfRange(0, out.size - 16)) to Base64.getEncoder().encodeToString(out.copyOfRange(out.size - 16, out.size))
    }
    @Test fun happBodyAuthenticatesSeparateTagAndKnownScopedKey() {
        val (body, tag) = encrypted(); val decoded = HappSubscriptionBody.decode(body, tag, url, HappSubscriptionBody.publicTestKeysFor(url))
        assertEquals(link, String(decoded)); assertEquals("vless", ServerImport.read(String(decoded)).single().protocol)
        reject { HappSubscriptionBody.decode(body, tag, url) }
        reject { HappSubscriptionBody.decode(body, tag, URI("https://other.invalid/sub?key=key02"), HappSubscriptionBody.publicTestKeysFor(url)) }
    }
    @Test fun happBodyMatchesIndependentNodeCryptoFixture() {
        val result = HappSubscriptionBody.decode("rHcmAXDY/0rJCois3qyDEzOSBz4=".toByteArray(), "ys0EtJdb95YidXWGRYYwJA==", url, HappSubscriptionBody.publicTestKeysFor(url))
        assertEquals("public-Happ-fixture\n", String(result))
    }
    @Test fun unknownKeyMissingIdDuplicateIdAndWrongBodyFailAtomically() {
        val (body, tag) = encrypted(); val data = ConnectionCatalogue(""); data.add(link); val before = data.serialize()
        for (uri in listOf(URI("https://example.invalid/sub"), URI("https://example.invalid/sub?key=private"), URI("https://example.invalid/sub?key=key02&key=key02"))) {
            reject { val decoded = HappSubscriptionBody.decode(body, tag, uri, HappSubscriptionBody.publicTestKeysFor(url)); data.add(String(decoded)) }
            assertEquals(before, data.serialize())
        }
        val badTag = Base64.getDecoder().decode(tag).also { it[0] = (it[0].toInt() xor 1).toByte() }
        reject { HappSubscriptionBody.decode(body, Base64.getEncoder().encodeToString(badTag), url, HappSubscriptionBody.publicTestKeysFor(url)) }
        val badBody = Base64.getDecoder().decode(body).also { it[0] = (it[0].toInt() xor 1).toByte() }
        reject { HappSubscriptionBody.decode(Base64.getEncoder().encode(badBody), tag, url, HappSubscriptionBody.publicTestKeysFor(url)) }
        reject { HappSubscriptionBody.decode(body, "", url, HappSubscriptionBody.publicTestKeysFor(url)) }
        assertEquals(before, data.serialize())
    }
    @Test fun plainResponseIsCopiedWithoutKeyLookup() {
        val bytes = link.toByteArray(); val decoded = HappSubscriptionBody.decode(bytes, null, url, HappSubscriptionBody.Keys { _, _ -> throw AssertionError() })
        assertArrayEquals(bytes, decoded); assertNotSame(bytes, decoded)
    }
    @Test fun documentedV2raytunWrapperPreservesEscapesAndImportsHttps() {
        val wrapped = "v2raytun://import/$link"
        assertEquals(wrapped, ImportLink.config(wrapped)); assertEquals(link, ServerImport.read(wrapped).single().original)
        assertEquals("/fixture+a", ServerImport.read(wrapped).single().outbound!!.getJSONObject("transport").getString("path"))
        var requested = ""
        val plan = ImportPlan.prepare("v2raytun://import/https://example.invalid/sub") { source -> requested = source; SubscriptionClient.Downloaded(ServerImport.read(link), JSONObject()) }
        assertEquals("https://example.invalid/sub", requested); assertEquals(1, plan.entries.size)
        assertEquals(2, ServerImport.read(wrapped + "\n" + link.replace("#Fixture", "#Other")).size)
    }
    @Test fun wrappersRejectNestedFilesOpaqueAndOversize() {
        for (payload in listOf("", "file:///private", "intent://private", "v2raytun://import/$link")) reject { CompatibilityImportLinks.unwrap("v2raytun://import/$payload") }
        reject { CompatibilityImportLinks.unwrap("v2raytun://crypt/private") }
        reject { CompatibilityImportLinks.unwrap("v2raytun://import/" + "x".repeat(65536)) }
    }
    @Test fun qwdttNewDefaultsRespectManualAndExplicitSourceAndNeverCopyToken() {
        val raw = JSONObject().put("type", "qwdtt").put("peer", "example.invalid:56000").put("password", "synthetic")
        var calls = 0
        val defaults: (JSONObject) -> JSONObject = { calls++; it.put("hash_source", "auto").put("hashes", JSONArray()).put("token", "DO_NOT_STORE") }
        val changed = JSONObject(CompatibilityImportQwdtt.applyNew(raw.toString(), defaults))
        assertEquals("auto", changed.getString("hash_source")); assertFalse(changed.has("token")); assertEquals(1, calls)
        raw.put("hashes", JSONArray().put("synthetic_fixture_call_hash"))
        assertEquals(JSONObject(raw.toString()).put("mode","raw").toString(), CompatibilityImportQwdtt.applyNew(raw.toString(), defaults)); assertEquals(1, calls)
        raw.remove("hashes"); raw.put("hash_source", "manual")
        assertEquals(JSONObject(raw.toString()).put("mode","raw").toString(), CompatibilityImportQwdtt.applyNew(raw.toString(), defaults)); assertEquals(1, calls)
    }
    @Test fun qwdttUriAndMixedListDefaultsRemainImportable() {
        val source = "qwdtt://config?peer=example.invalid%3A56000&password=synthetic"
        val manualDefaults: (JSONObject) -> JSONObject = { it.put("hash_source", "manual").put("hashes", JSONArray().put("synthetic_fixture_call_hash")) }
        val entries = ServerImport.readNew(source + "\n" + link, manualDefaults)
        assertEquals(2, entries.size); assertEquals("manual", entries.first().carrier!!.getString("hash_source"))
        assertEquals(link, entries.last().original)
        assertEquals(1, ImportPlan.prepareNew(source, { throw AssertionError() }, manualDefaults).entries.size)
    }
    @Test fun autoQwdttIsStoredWithoutCallsOrAccountCredentials() {
        val plan = ImportPlan.prepareNew("qwdtt://config?peer=example.invalid%3A56000&password=synthetic", { throw AssertionError() }) {
            it.put("hash_source", "auto").put("hashes", JSONArray()).put("token", "DO_NOT_STORE")
        }
        val catalogue = ConnectionCatalogue(""); catalogue.add(plan.manual)
        val entry = catalogue.entries().single()
        assertEquals("auto", entry.carrier!!.getString("hash_source")); assertEquals(0, entry.carrier!!.getJSONArray("hashes").length()); assertFalse(catalogue.serialize().contains("DO_NOT_STORE"))
        assertEquals("auto", ServerImport.read(ConnectionEdits.share(entry, true)).single().carrier!!.getString("hash_source"))
        val manual = ConnectionEdits.snapshot(entry); manual.getJSONObject("carrier").put("hashes", JSONArray().put("synthetic_fixture_call_hash"))
        assertEquals("manual", ConnectionEdits.apply(entry, manual).carrier!!.getString("hash_source"))
    }
    private fun wg(dns: String): ServerImport.Server = ServerImport.read("[Interface]\nPrivateKey = ${Base64.getEncoder().encodeToString(ByteArray(32){1})}\nAddress = 10.0.0.2/32\nDNS = $dns\n[Peer]\nPublicKey = ${Base64.getEncoder().encodeToString(ByteArray(32){2})}\nEndpoint = example.invalid:51820\nAllowedIPs = 0.0.0.0/0").single()
    @Test fun wgDnsUsesFirstResolverOverTunnelAndHasValidNativeMarker() {
        val entry = wg("10.0.0.53, 1.1.1.1")
        val config = ServerImport.build(listOf(entry), entry.id, false, 60, 50, "Cloudflare")
        val resolver = config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("10.0.0.53", resolver.getString("server")); assertEquals(CompatibilityImportDns.TAG, resolver.getString("tag")); assertEquals(ServerImport.tag(entry), resolver.getString("detour"))
        assertEquals(CompatibilityImportDns.TAG, config.getJSONObject("route").getString("default_domain_resolver"))
        assertEquals(2, entry.profileDns.size)
    }
    @Test fun automaticMixedProfilesHaveExplicitGlobalDnsPolicy() {
        val first = wg("1.1.1.1"); val other = ServerImport.read(link).single()
        assertFalse(CompatibilityImportDns.hasOverride(ServerImport.build(listOf(first, other), "", true, 60, 50, "Cloudflare")))
        val second = wg("1.1.1.1").let { ServerImport.Server(it.original + "\n#unique", "Second", it.protocol, it.host, it.port, it.outbound, it.endpoint, it.configuration, it.carrier, it.profileDns) }
        assertTrue(CompatibilityImportDns.hasOverride(ServerImport.build(listOf(first, second), "", true, 60, 50, "Cloudflare")))
    }
    @Test fun profileDnsEnforceRetainsScopedRulesAndReplacesOnlyDefaultCatchall() {
        val entry = wg("1.1.1.1"); val config = ServerImport.build(listOf(entry), entry.id, false, 60, 50, "Cloudflare")
        config.getJSONObject("dns").put("final", "chosen").put("rules", JSONArray().put(JSONObject().put("action", "route").put("domain_suffix", JSONArray().put("example.invalid")).put("server", "chosen")).put(JSONObject().put("action", "route").put("server", "chosen")))
        CompatibilityImportDns.enforce(config)
        val rules = config.getJSONObject("dns").getJSONArray("rules")
        assertEquals("chosen", rules.getJSONObject(0).getString("server")); assertEquals(CompatibilityImportDns.TAG, rules.getJSONObject(1).getString("server"))
    }
    @Test fun happRoutingLinksRetainInlineGeoOrderAndAreNeverConnectionProfiles() {
        val raw = JSONObject().put("RouteOrder", "direct-proxy-block").put("DirectSites", JSONArray().put("domain:example.invalid").put("geosite:ru")).put("ProxySites", JSONArray().put("geosite:category-ads-all").put("full:proxy.invalid"))
        val link = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(raw.toString().toByteArray())
        val request = HappRoutingLinks.parse(link)
        assertEquals("onadd", request.action); assertEquals(raw.toString(), request.document!!.toString()); assertEquals("domain:example.invalid", request.document!!.getJSONArray("DirectSites").getString(0))
        assertEquals("off", HappRoutingLinks.parse("happ://routing/off").action)
        reject { ServerImport.read(link) }; reject { HappRoutingLinks.parse("happ://routing/onadd/PRIVATE_BAD") }
    }
    @Test fun legacyCsqttPreservesIpv6AndPercentPasswordWithExplicitManualCalls() {
        val source = "csqtt://synthetic%3Apass%2Bword@[2001:db8::1]:46000"
        val entry = ServerImport.read(CompatibilityImportCsqttLegacy.materialize(source, listOf("synthetic_fixture_call_hash"))).single()
        assertEquals("2001:db8::1", entry.host); assertEquals("synthetic:pass+word", entry.carrier!!.getString("password")); assertEquals("manual", entry.carrier!!.getString("hash_source"))
        reject { CompatibilityImportCsqttLegacy.materialize(source, emptyList()) }
        try { ServerImport.read(source); fail() } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("распознана")); assertFalse(error.message!!.contains("pass")) }
    }
    @Test fun legacyCsqttSuppliedCallsDelegateAndOverrideExternalDefaults() {
        val password = java.net.URLEncoder.encode("synthetic +:ёж", "UTF-8").replace("+", "%20")
        val calls = (1..6).map { "synthetic_fixture_call_hash_$it" }
        val source = "csqtt://$password@[2001:db8::1]:46000?hashes=${calls.joinToString("+")}&name=Legacy"
        val entry = ServerImport.read(source).single()
        assertEquals("2001:db8::1", entry.host); assertEquals("synthetic +:ёж", entry.carrier!!.getString("password")); assertEquals("Legacy", entry.name)
        assertEquals(calls, (0 until 6).map { entry.carrier!!.getJSONArray("hashes").getString(it) })
        val materialized = ServerImport.read(CompatibilityImportCsqttLegacy.materialize(source, listOf("different_fixture_call_hash"))).single()
        assertEquals(6, materialized.carrier!!.getJSONArray("hashes").length()); assertEquals(calls[0], materialized.carrier!!.getJSONArray("hashes").getString(0))
        assertEquals(6, ServerImport.read(source.replace("hashes=", "vkHashes=")).single().carrier!!.getJSONArray("hashes").length())
    }
    @Test fun legacyCsqttRejectsUnknownDuplicateOrAmbiguousQueryParameters() {
        val source = "csqtt://synthetic@fixture.invalid:46000?hashes=synthetic_fixture_call_hash"
        for (suffix in listOf("&unknown=1", "&hashes=other_fixture_call_hash", "&vkHashes=other_fixture_call_hash", "&name=One&name=Two", "&hash_source=auto")) reject { ServerImport.read(source + suffix) }
    }
}
