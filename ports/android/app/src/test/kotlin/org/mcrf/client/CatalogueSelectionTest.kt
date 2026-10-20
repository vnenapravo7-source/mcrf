package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class CatalogueSelectionTest {
    private val a = "socks://127.0.0.1:18081#A"
    private val b = "socks://127.0.0.1:18082#B"
    @Test fun nothingIsAutomaticallySelected() { val data = ConnectionCatalogue(""); data.add("$a\n$b"); assertTrue(data.autoIds().isEmpty()) }
    @Test fun selectionSurvivesSerializationAndZeroMillisecondSuccess() {
        val data = ConnectionCatalogue(""); data.add("$a\n$b"); val id = data.entries()[0].id
        data.setAuto(id, true); data.recordProbe(id, 0, 12345)
        val restored = ConnectionCatalogue(data.serialize())
        assertEquals(setOf(id), restored.autoIds()); assertEquals(0, restored.probes().getJSONObject(id).getLong("delay")); assertEquals(12345, restored.probes().getJSONObject(id).getLong("at"))
    }
    @Test fun deselectionDoesNotSelectAnotherNode() { val data = ConnectionCatalogue(""); data.add("$a\n$b"); val id = data.entries()[0].id; data.setAuto(id, true); data.setAuto(id, false); assertTrue(data.autoIds().isEmpty()) }
    @Test fun removedServerLosesProbeAndSelection() { val data = ConnectionCatalogue(""); data.add(a); val id = data.entries()[0].id; data.setAuto(id, true); data.recordProbe(id, 10, 100); data.remove(id); assertTrue(data.autoIds().isEmpty()); assertFalse(data.probes().has(id)) }
    @Test fun oldCataloguesStillImportAndNoProbeIsInvented() { val data = ConnectionCatalogue("{\"version\":1,\"servers\":[\"$a\"],\"subscriptions\":[]}"); assertEquals(1, data.entries().size); assertTrue(data.autoIds().isEmpty()); assertEquals(0, data.probes().length()) }
    @Test fun subscriptionEditingPreservesIdentityAndManualNodes() {
        val data = ConnectionCatalogue(""); data.add(a); data.replaceSubscription("https://example.com/old", ServerImport.read(b)); val id = data.subscriptions()[0].getString("id")
        data.editSubscription(id, "https://example.com/new", ServerImport.read(b)); assertEquals(id, data.subscriptions()[0].getString("id")); assertEquals("https://example.com/new", data.subscriptions()[0].getString("url")); assertEquals(2, data.entries().size)
    }
    @Test fun emptyEditedSubscriptionPreservesOldCatalogue() {
        val data = ConnectionCatalogue(""); data.replaceSubscription("https://example.com/sub", ServerImport.read(a)); val before = data.serialize()
        try { data.editSubscription(data.subscriptions()[0].getString("id"), "https://example.com/new", emptyList()); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, data.serialize())
    }
    @Test fun forcedVpnAppsPrecedeRussiaAndDefaultRoutes() {
        val state = RouteRules.defaults(); state.getJSONArray("vpnApps").put("org.example.app")
        state.getJSONArray("rules").put(JSONObject().put("id", "ru").put("name", "Russia").put("mode", "addresses").put("target", "direct").put("text", "ru"))
        val config = ProfileConfig.build("{\"outbounds\":[{\"tag\":\"p\",\"type\":\"socks\",\"server\":\"127.0.0.1\",\"server_port\":18081}],\"route\":{\"final\":\"p\"}}", true, 10808)
        val rules = RouteRules.apply(config, state, true).getJSONObject("route").getJSONArray("rules")
        assertEquals("p", rules.getJSONObject(3).getString("outbound")); assertEquals("org.example.app", rules.getJSONObject(3).getJSONArray("package_name").getString(0)); assertTrue(rules.getJSONObject(4).has("domain_suffix"))
    }
}
