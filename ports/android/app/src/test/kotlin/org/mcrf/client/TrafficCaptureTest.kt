package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TrafficCaptureTest {
    private class Memory : LocalDiagnosticStorage {
        var raw = ""; var writes = 0
        override fun read() = raw
        override fun write(value: String) { raw = value; writes++ }
        override fun delete() { raw = "" }
    }
    private class Fixture(val memory: Memory = Memory(), val maxCount: Int = 1000, val maxBytes: Int = 512 * 1024) {
        var elapsed = 100L; var wall = 10_000L
        val store = TrafficJournalStore(memory, { wall }, maxCount = maxCount, maxBytes = maxBytes)
        val recorder = TrafficCaptureRecorder(store, { elapsed }, { wall })
        fun event(uid: Int? = null) = TrafficJournalStore.Observation(wall, "192.0.2.1", 443, uid, protocol = TrafficJournalStore.Protocol.TCP)
    }
    @Test fun onlySixRecordingIntervalsAreOfferedAndInternalBoundsRemainCompatible() {
        val expected = listOf(30L, 60, 180, 300, 600, 900)
        assertEquals(expected, TrafficCapturePolicy.presets.map { it.seconds })
        assertEquals(listOf("30 сек", "1 мин", "3 мин", "5 мин", "10 мин", "15 мин"), TrafficCapturePolicy.presets.map { it.label })
        assertEquals(expected.size, expected.distinct().size)
        // Existing persisted/internal sessions remain readable; no custom interval is offered by the UI.
        for (seconds in listOf(1L, 37, 86_400)) TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.ONLY, seconds = seconds)
        for (seconds in listOf(-1L, 86_401, Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
            TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.ONLY, seconds = seconds)
        }
    }
    @Test fun singleVpnAdmissionAndSafeTransitions() {
        fun gate(mode: TrafficCapturePolicy.Mode, foreign: Boolean = false, active: Boolean = false, tun: Boolean = false, busy: Boolean = false) =
            TrafficCapturePolicy.admit(mode, foreign, active, tun, busy)
        for (mode in TrafficCapturePolicy.Mode.entries) {
            assertEquals(TrafficCapturePolicy.Admission.FOREIGN_VPN, gate(mode, foreign = true))
            assertEquals(TrafficCapturePolicy.Admission.BUSY, gate(mode, busy = true))
        }
        assertEquals(TrafficCapturePolicy.Admission.DIRECT, gate(TrafficCapturePolicy.Mode.ONLY))
        assertEquals(TrafficCapturePolicy.Admission.SERVER_CONFLICT, gate(TrafficCapturePolicy.Mode.ONLY, active = true, tun = true))
        assertEquals(TrafficCapturePolicy.Admission.ATTACH, gate(TrafficCapturePolicy.Mode.CONNECTED, active = true, tun = true))
        assertEquals(TrafficCapturePolicy.Admission.CONNECT, gate(TrafficCapturePolicy.Mode.CONNECTED))
        assertEquals(TrafficCapturePolicy.Admission.SERVER_CONFLICT, gate(TrafficCapturePolicy.Mode.CONNECTED, active = true))
    }
    @Test fun retainedTunAndRetiringWorkersCannotBeReplacedByCapture() {
        assertEquals(TrafficCapturePolicy.Admission.SERVER_CONFLICT,
            TrafficCapturePolicy.admit(TrafficCapturePolicy.Mode.ONLY, false, false, true, false))
        for (mode in TrafficCapturePolicy.Mode.entries) for (tun in listOf(false, true))
            assertEquals(TrafficCapturePolicy.Admission.SERVER_CONFLICT,
                TrafficCapturePolicy.admit(mode, false, false, tun, false, serverRetiring = true))
    }
    @Test fun bothModesUseIdenticalSessionsFiltersAndStorage() {
        for (mode in TrafficCapturePolicy.Mode.entries) {
            val f = Fixture()
            val token = f.recorder.start(TrafficCapturePolicy.Options(mode, setOf("org.example.one"), 0, true))
            assertFalse(f.recorder.record(token, null, f.event()))
            assertFalse(f.recorder.record(token, listOf("org.example.two"), f.event(42)))
            assertTrue(f.recorder.record(token, listOf("org.example.one"), f.event(42)))
            assertEquals(1, f.store.entries().size)
            assertEquals(token, f.store.entries().single().session)
            assertEquals(token, JSONObject(f.store.export()).getJSONArray("entries").getJSONObject(0).getString("session"))
            f.recorder.stop()
            val writes = f.memory.writes
            assertFalse(f.recorder.record(token, listOf("org.example.one"), f.event(42)))
            assertEquals(writes, f.memory.writes)
        }
    }
    @Test fun timerUsesElapsedClockAndRejectsLateEventsAtExactBoundary() {
        val f = Fixture()
        val token = f.recorder.start(TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.ONLY, seconds = 30))
        f.wall += 86_400_000 // wall clock jumps do not end the timer
        f.elapsed += 29_999
        assertEquals(1L, f.recorder.remaining())
        assertTrue(f.recorder.record(token, null, f.event()))
        f.elapsed++
        assertFalse(f.recorder.record(token, null, f.event()))
        assertNull(f.recorder.current())
        assertFalse(f.store.enabled())
    }
    @Test fun manualStopDeleteAndNewSessionFenceQueuedOldWork() {
        val f = Fixture()
        val old = f.recorder.start(TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.ONLY))
        f.elapsed += 100_000_000
        assertFalse(f.recorder.expire()); assertNull(f.recorder.remaining())
        assertTrue(f.recorder.record(old, null, f.event()))
        f.recorder.delete()
        assertEquals("", f.memory.raw)
        assertFalse(f.recorder.record(old, null, f.event()))
        assertEquals("", f.memory.raw)
        val next = f.recorder.start(TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.CONNECTED))
        assertNotEquals(old, next)
        assertFalse(f.recorder.record(old, null, f.event()))
        assertTrue(f.recorder.record(next, null, f.event()))
        assertEquals(next, f.store.entries().single().session)
    }
    @Test fun stopRacingWithIngestionDoesNotResurrectRecording() {
        val f = Fixture()
        val token = f.recorder.start(TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.ONLY))
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val jobs = (0..50).map { pool.submit { go.await(); f.recorder.record(token, null, f.event()) } }
        val stop = pool.submit { go.await(); f.recorder.stop() }
        go.countDown(); jobs.forEach { it.get(10, TimeUnit.SECONDS) }; stop.get(10, TimeUnit.SECONDS)
        pool.shutdown(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        val writes = f.memory.writes
        assertFalse(f.recorder.record(token, null, f.event()))
        assertEquals(writes, f.memory.writes); assertFalse(f.store.enabled())
    }
    @Test fun boundedRetentionAndExpiryKeepMetadataOnly() {
        val f = Fixture(maxCount = 3, maxBytes = 1800)
        val token = f.recorder.start(TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.CONNECTED))
        repeat(20) { f.wall++; assertTrue(f.recorder.record(token, null, f.event())) }
        assertEquals(3, f.store.entries().size)
        assertTrue(f.memory.raw.toByteArray().size <= 1800)
        val row = JSONObject(f.store.export()).getJSONArray("entries").getJSONObject(0)
        assertFalse(row.has("payload")); assertFalse(row.has("url")); assertFalse(row.has("headers"))
        f.wall += TrafficJournalStore.RETENTION_MS + 1
        assertTrue(f.store.entries().isEmpty())
    }
    @Test fun sharedUidPackagesAreRetainedAndDnsIsNotInvented() {
        val f = Fixture()
        val token = f.recorder.start(TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.ONLY, setOf("org.example.one"), selectedOnly = true))
        val packages = listOf("org.example.one", "org.example.shared")
        assertTrue(f.recorder.record(token, packages, f.event(42).copy(packages = packages)))
        assertEquals(packages, f.store.entries().single().packages)
        assertNull(f.store.entries().single().domain)
        f.recorder.dns(token, "192.0.2.1", "example.org", 42, packages, 1000)
        assertTrue(f.recorder.record(token, packages, f.event(42)))
        assertEquals("example.org", f.store.entries().first().domain)
        f.wall += 1001
        assertTrue(f.recorder.record(token, packages, f.event(42)))
        assertNull(f.store.entries().first().domain)
    }
    @Test fun unattributedDnsAnswerIsExplicitAndExcludedFromAppScope() {
        val f = Fixture()
        val token = f.recorder.start(TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.ONLY))
        assertTrue(f.recorder.record(token, null, f.event().copy(port = null, domain = "example.org", event = "dns-answer")))
        val row = JSONObject(f.store.export()).getJSONArray("entries").getJSONObject(0)
        assertTrue(row.isNull("uid")); assertTrue(row.isNull("port"))
        assertEquals("dns-answer", row.getString("event"))
        assertEquals("DNS-answer-unattributed", row.getString("domainAttribution"))
        assertThrows(IllegalArgumentException::class.java) { f.recorder.record(token, null, f.event().copy(domain = "https://secret/?token=value")) }
    }
    @Test fun persistedOptInDoesNotCreateAnotherLiveSession() {
        val f = Fixture()
        f.store.setEnabled(true)
        val fresh = TrafficCaptureRecorder(f.store, { f.elapsed }, { f.wall })
        assertNull(fresh.current()); assertFalse(fresh.record("stale", null, f.event()))
        val before = f.memory.raw
        f.memory.raw = "invalid encrypted diagnostic content"
        assertThrows(IllegalStateException::class.java) { fresh.start(TrafficCapturePolicy.Options(TrafficCapturePolicy.Mode.ONLY)) }
        assertEquals("invalid encrypted diagnostic content", f.memory.raw)
        assertNotEquals(before, f.memory.raw)
    }
    @Test fun directConfigHasNoServerTunnelOrPayloadLogAndForcesOwnerLookup() {
        val config = TrafficCaptureConfig.direct()
        assertTrue(config.getJSONObject("log").getBoolean("disabled"))
        assertEquals(1, config.getJSONArray("inbounds").length())
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertEquals("direct", config.getJSONArray("outbounds").getJSONObject(0).getString("type"))
        assertFalse(config.getJSONArray("outbounds").getJSONObject(0).has("server"))
        assertTrue(config.getJSONObject("route").getBoolean("find_process"))
        assertEquals("local", config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("type"))
    }
    @Test fun recordingPreparationPreservesServerRoutesAndOriginalSnapshot() {
        val config = JSONObject("""{"route":{"final":"server","rules":[{"package_name":["org.example.one"],"outbound":"direct"}]},"outbounds":[{"type":"vless","tag":"server"}],"dns":{"final":"existing"}}""")
        val before = config.toString()
        val next = TrafficCaptureConfig.observeNewServer(config)
        assertEquals(before, config.toString())
        assertEquals(config.getJSONArray("outbounds").toString(), next.getJSONArray("outbounds").toString())
        assertEquals(config.getJSONObject("dns").toString(), next.getJSONObject("dns").toString())
        assertEquals(config.getJSONObject("route").getJSONArray("rules").toString(), next.getJSONObject("route").getJSONArray("rules").toString())
        assertEquals("server", next.getJSONObject("route").getString("final"))
        assertTrue(next.getJSONObject("route").getBoolean("find_process"))
    }
}
