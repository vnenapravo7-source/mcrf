package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class AutoSelectionTest {
    private val now = 1_000_000L
    private fun entry(kind: String, name: String = kind) = ServerImport.Server("$kind://fixture/$name", name, kind, "fixture.invalid", 443, JSONObject().put("type", kind), null, null,
        if(kind in AutoSelection.ENGINE_PRIORITY)JSONObject().put("type",kind)else null)
    private fun probes(entries: List<ServerImport.Server>) = JSONObject().apply { entries.forEachIndexed { i, e -> put(e.id, JSONObject().put("delay", (5-i)*30).put("at", now)) } }
    @Test fun bsUsesExactProtocolPriorityNotBestPing() {
        val entries = listOf(entry("openflux"), entry("wdtt"), entry("qwdtt"), entry("csqtt"), entry("vless"),entry(S3BypassProfile.KIND))
        val measured = probes(entries)
        measured.getJSONObject(entries[0].id).put("delay", 0); measured.getJSONObject(entries[3].id).put("delay", 500)
        assertEquals(AutoSelection.ENGINE_PRIORITY, AutoSelection.proven(entries, entries.map { it.id }.toSet(), measured, now, AutoSelection.BS_ENGINES).map { it.protocol })
    }
    @Test fun unmarkedNodesNeverEnterEitherPolicy() {
        val entries = listOf(entry("csqtt"), entry("qwdtt"))
        assertEquals(listOf("qwdtt"), AutoSelection.proven(entries, setOf(entries[1].id), probes(entries), now, AutoSelection.BS_ENGINES).map { it.protocol })
        assertTrue(AutoSelection.proven(entries, emptySet(), probes(entries), now, AutoSelection.BS_ENGINES).isEmpty())
    }
    @Test fun failuresMissingStaleAndFutureMeasurementsAreRejected() {
        val entries = listOf(entry("csqtt"), entry("qwdtt"), entry("wdtt"), entry("openflux"))
        val measured = probes(entries)
        measured.getJSONObject(entries[0].id).put("delay", -1)
        measured.remove(entries[1].id)
        measured.getJSONObject(entries[2].id).put("at", now-300_001)
        measured.getJSONObject(entries[3].id).put("at", now+1)
        assertTrue(AutoSelection.proven(entries, entries.map { it.id }.toSet(), measured, now, AutoSelection.BS_ENGINES).isEmpty())
    }
    @Test fun zeroMillisecondsIsSuccessful() {
        val node = entry("socks"); val results = JSONObject().put(node.id, JSONObject().put("delay", 0).put("at", now))
        assertEquals(listOf(node), AutoSelection.proven(listOf(node), setOf(node.id), results, now))
    }
    @Test fun ordinaryPoolCannotFallBackToBsEngineOrJsonProfile() {
        val node = entry("vless"); val engine = entry("csqtt")
        val profile = ServerImport.Server("json-fixture", "JSON", "sing-box", "", 0, null, null, JSONObject())
        val entries = listOf(node, engine, profile)
        assertEquals(listOf(node), AutoSelection.proven(entries, entries.map { it.id }.toSet(), probes(entries), now))
    }
}
