package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ClientPrimaryProbeTest {
    private fun id(value: Int) = value.toString(16).padStart(64, '0')

    private fun config() = JSONObject().put("inbounds", JSONArray().put(JSONObject().put("type", "tun")))
        .put("experimental", JSONObject().put("clash_api", JSONObject().put("external_controller", "0.0.0.0:9090")))
        .put("ntp", JSONObject().put("enabled", true)).put("services", JSONArray().put(JSONObject().put("type", "ssm-api")))
        .put("dns", JSONObject().put("servers", JSONArray().put("unchanged.invalid")))
        .put("outbounds", JSONArray().put(JSONObject().put("type", "socks").put("tag", "carrier")
            .put("server", "127.0.0.1").put("server_port", 1).put("version", "5")
            .put("opaque", JSONObject().put("secret", "original-secret")))
            .put(JSONObject().put("type", "trojan").put("tag", "ordinary").put("password", "keep-original")
                .put("tls", JSONObject().put("server_name", "unchanged.invalid"))))

    @Test fun privateIngressPreservesAllOutboundDataAndNeverMutatesSource() {
        val original = config()
        val before = original.toString()
        val first = ClientPrimaryProbeConfig.isolated(original, 43210)
        val second = ClientPrimaryProbeConfig.isolated(original, 43211)
        assertEquals(before, original.toString())
        assertEquals(original.getJSONArray("outbounds").toString(), first.getJSONArray("outbounds").toString())
        assertEquals(original.getJSONObject("dns").toString(), first.getJSONObject("dns").toString())
        assertFalse(first.has("experimental")); assertFalse(first.has("services")); assertFalse(first.has("ntp"))
        val inbound = first.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(1, first.getJSONArray("inbounds").length())
        assertEquals("mixed", inbound.getString("type")); assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(43210, inbound.getInt("listen_port"))
        val credentials = inbound.getJSONArray("users").getJSONObject(0)
        assertTrue(credentials.getString("password").isNotEmpty())
        assertNotEquals(credentials.toString(), second.getJSONArray("inbounds").getJSONObject(0).getJSONArray("users").getJSONObject(0).toString())
    }

    @Test fun eachCarrierStartsFromOriginalSnapshotAndOnlyItsOwnBridgeChanges() {
        val original = config()
        val before = original.toString()
        val first = ClientPrimaryProbeConfig.carrier(original, "carrier", 23456, "one", "secret-one")
        val second = ClientPrimaryProbeConfig.carrier(original, "carrier", 23457, "two", "secret-two")
        assertEquals(before, original.toString())
        assertEquals("one", first.getJSONArray("outbounds").getJSONObject(0).getString("username"))
        assertEquals("two", second.getJSONArray("outbounds").getJSONObject(0).getString("username"))
        assertEquals("original-secret", second.getJSONArray("outbounds").getJSONObject(0).getJSONObject("opaque").getString("secret"))
        assertEquals(original.getJSONArray("outbounds").getJSONObject(1).toString(), second.getJSONArray("outbounds").getJSONObject(1).toString())
        assertThrows(IllegalArgumentException::class.java) { ClientPrimaryProbeConfig.carrier(original, "ordinary", 10, "", "") }
        assertThrows(IllegalArgumentException::class.java) { ClientPrimaryProbeConfig.carrier(original, "missing", 10, "", "") }
    }

    @Test fun all256ParallelResultsAreCoveredAndProgressIsExactlyOnceAndOrdered() {
        val ids = (0 until 256).map(::id)
        val events = mutableListOf<Triple<Int, String, Long>>()
        val results = ClientPrimaryProbeResults(ids) { count, key, delay -> events.add(Triple(count, key, delay)) }
        val workers = Executors.newFixedThreadPool(4)
        try {
            ids.forEachIndexed { index, key -> workers.submit { results.record(key, index.toLong()); results.record(key, -1) } }
            workers.shutdown()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            val complete = results.finish()
            assertEquals(ids.toSet(), complete.keys)
            ids.forEachIndexed { index, key -> assertEquals(index.toLong(), complete.getValue(key)) }
            assertEquals((1..256).toList(), events.map { it.first })
            assertEquals(256, events.map { it.second }.toSet().size)
        } finally { workers.shutdownNow() }
    }

    @Test fun cancellationAndFailuresFillEveryUnmeasuredIdWithoutOverwritingSuccesses() {
        val ids = (0 until 256).map(::id)
        val events = mutableListOf<String>()
        val results = ClientPrimaryProbeResults(ids) { _, key, _ -> events.add(key) }
        results.record(id(0), 0); results.record(id(1), 37); results.record(id(2), -9)
        val complete = results.finish()
        assertEquals(256, complete.size); assertEquals(256, events.size)
        assertEquals(0L, complete[id(0)]); assertEquals(37L, complete[id(1)])
        assertTrue(ids.drop(2).all { complete[it] == -1L })
        results.finish(); results.record(id(0), -1)
        assertEquals(256, events.size)
        assertThrows(IllegalArgumentException::class.java) { results.record(id(256), 1) }
    }

    @Test fun receiverFailureCannotInterruptFullCoverageAndInvalidRequestsFailClosed() {
        val results = ClientPrimaryProbeResults(listOf(id(0), id(1))) { _, _, _ -> throw IllegalStateException("receiver closed") }
        results.record(id(0), 1)
        assertEquals(mapOf(id(0) to 1L, id(1) to -1L), results.finish())
        for (ids in listOf(emptyList(), listOf(id(1), id(1)), (0..256).map(::id), listOf("secret-url"))) {
            val error = assertThrows(IllegalArgumentException::class.java) { ClientPrimaryProbeResults(ids) { _, _, _ -> } }
            assertEquals("PROBE_SERVER_IDS", error.message)
        }
    }

    @Test fun recreatedServiceQuiesceCancelsOriginalRunAndWaitsForItsNativeCleanup() {
        val lifetime = ClientPrimaryProbeLifecycle()
        var cancelled = false
        val run = requireNotNull(lifetime.begin { cancelled = true })
        var acknowledged = false
        var returned: Map<String, Long>? = null
        // A new service instance uses the same process-wide lifetime after stopService.
        lifetime.quiesce { cleaned, values -> acknowledged = cleaned; returned = values }
        assertTrue(cancelled); assertFalse(acknowledged)
        assertNull(lifetime.begin { fail("must not replace a still cleaning run") })
        val full = (0 until 256).associate { id(it) to if (it == 0) 17L else -1L }
        lifetime.finish(run, true, full)
        assertTrue(acknowledged); assertEquals(full, returned)
        lifetime.quiesce { cleaned, values -> assertTrue(cleaned); assertEquals(full, values) }
        assertNotNull(lifetime.begin { })
    }

    @Test fun failedNativeCleanupNeverBecomesAnIdleSuccessAcknowledgement() {
        val lifetime = ClientPrimaryProbeLifecycle()
        val run = requireNotNull(lifetime.begin { })
        val replies = mutableListOf<Boolean>()
        lifetime.quiesce { cleaned, _ -> replies.add(cleaned) }
        assertTrue(replies.isEmpty())
        lifetime.finish(run, false, mapOf(id(0) to -1L))
        lifetime.quiesce { cleaned, values -> replies.add(cleaned); assertEquals(setOf(id(0)), values.keys) }
        assertEquals(listOf(false, false), replies)
        assertNull(lifetime.begin { })
    }

    @Test fun multipleQuiesceWaitersAndDisconnectedReceiversCannotLoseCleanupAcknowledgement() {
        val lifetime = ClientPrimaryProbeLifecycle()
        var cancellations = 0
        val run = requireNotNull(lifetime.begin { cancellations++ })
        var delivered = false
        lifetime.quiesce { _, _ -> throw IllegalStateException("receiver disconnected") }
        lifetime.quiesce { cleaned, _ -> delivered = cleaned }
        assertEquals(2, cancellations); assertFalse(delivered)
        lifetime.finish(run, true, emptyMap())
        assertTrue(delivered)
        assertThrows(IllegalStateException::class.java) { lifetime.finish(run, true, emptyMap()) }
    }
}
