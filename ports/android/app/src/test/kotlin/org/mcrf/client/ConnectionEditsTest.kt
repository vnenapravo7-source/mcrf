package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionEditsTest {
    private val source = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#Source"
    private fun edit(data: ConnectionCatalogue): String { val node = data.entries().single(); val options = ConnectionEdits.snapshot(node).put("name", "Edited"); options.getJSONObject("outbound").put("server", "other.example").put("server_port", 8443); data.edit(node.id, options); return node.id }
    @Test fun editPreservesIdentityAndOriginalSubscription() {
        val data = ConnectionCatalogue(""); data.replaceSubscription("https://example.com/sub", ServerImport.read(source)); val id = edit(data)
        assertEquals(id, data.entries().single().id); assertEquals(source, data.entries().single().original); assertEquals("other.example", data.entries().single().host)
        assertEquals(source, data.subscriptions().single().getJSONArray("servers").getString(0))
    }
    @Test fun refreshDoesNotResetLocalEdits() { val data = ConnectionCatalogue(""); data.replaceSubscription("https://example.com/sub", ServerImport.read(source)); edit(data); data.replaceSubscription("https://example.com/sub", ServerImport.read(source)); assertEquals("Edited", data.entries().single().name) }
    @Test fun editedServerMustBeReprobed() { val data = ConnectionCatalogue(""); data.add(source); val id = data.entries().single().id; data.setAuto(id, true); data.recordProbe(id, 10, 1); edit(data); assertFalse(data.probes().has(id)); assertTrue(id in data.autoIds()) }
    @Test fun resetReturnsUpstreamData() { val data = ConnectionCatalogue(""); data.add(source); val id = edit(data); data.edit(id, null); assertEquals("example.com", data.entries().single().host); assertFalse(data.edited(id)) }
    @Test fun invalidEditDoesNotMutateModel() { val data = ConnectionCatalogue(""); data.add(source); val before = data.serialize(); val node = data.entries().single(); val options = ConnectionEdits.snapshot(node); options.getJSONObject("outbound").put("server_port", 70000); try { data.edit(node.id, options); fail() } catch (_: IllegalArgumentException) { }; assertEquals(before, data.serialize()) }
    @Test fun muxPoliciesCannotConflict() { val node = ServerImport.read(source).single(); val options = ConnectionEdits.snapshot(node); options.getJSONObject("outbound").put("multiplex", JSONObject().put("enabled", true).put("max_connections", 4).put("max_streams", 8)); try { ConnectionEdits.apply(node, options); fail() } catch (_: IllegalArgumentException) { } }
    @Test fun editedShareContainsCurrentHostAndName() { val data = ConnectionCatalogue(""); data.add(source); edit(data); val node = data.entries().single(); val shared = ConnectionEdits.share(node, true); assertTrue(shared.contains("other.example")); assertEquals("Edited", ServerImport.read(shared).single().name) }
    @Test fun changedSourcePrunesStaleEdits() { val data = ConnectionCatalogue(""); data.replaceSubscription("https://example.com/sub", ServerImport.read(source)); val id = edit(data); data.replaceSubscription("https://example.com/sub", ServerImport.read(source.replace("example.com:443", "new.example:443"))); assertFalse(data.edited(id)); assertEquals("new.example", data.entries().single().host) }
}
