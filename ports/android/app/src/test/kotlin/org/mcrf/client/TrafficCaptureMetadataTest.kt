package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrafficCaptureMetadataTest {
    private class Memory : LocalDiagnosticStorage {
        var raw = ""
        override fun read() = raw
        override fun write(value: String) { raw = value }
        override fun delete() { raw = "" }
    }
    @Test fun sourceAndKnownSizeSurviveRoundTripWithoutPayload() {
        val storage = Memory()
        val store = TrafficJournalStore(storage, { 10_000L })
        store.setEnabled(true)
        store.ingest(TrafficJournalStore.Observation(10_000, "192.0.2.1", 443, 42,
            domain = "EXAMPLE.ORG.", bytes = 1234, domainSource = TrafficJournalStore.DomainSource.SNI))
        val entry = TrafficJournalStore(storage, { 10_000L }).entries().single()
        assertEquals("example.org", entry.domain); assertEquals(1234L, entry.bytes)
        assertEquals(TrafficJournalStore.DomainSource.SNI, entry.domainSource)
        val json = JSONObject(store.export()).getJSONArray("entries").getJSONObject(0)
        assertEquals("SNI", json.getString("domainAttribution"))
        assertFalse(json.has("payload")); assertFalse(json.has("headers"))
    }
    @Test fun legacyRowsStayReadableAndUnknownSizeIsNotZero() {
        val storage = Memory()
        val store = TrafficJournalStore(storage, { 10_000L })
        store.setEnabled(true)
        store.ingest(TrafficJournalStore.Observation(10_000, "192.0.2.1"))
        val doc = JSONObject(storage.raw)
        doc.getJSONArray("entries").getJSONObject(0).apply { remove("bytes"); remove("domainSource") }
        storage.raw = doc.toString()
        val legacy = TrafficJournalStore(storage, { 10_000L }).entries().single()
        assertNull(legacy.bytes); assertNull(legacy.domain)
        assertEquals(TrafficJournalStore.DomainSource.UNKNOWN, legacy.domainSource)
    }
    @Test fun directHostTakesPrecedenceAndDnsNeverCrossesUidOrAmbiguity() {
        val storage = Memory()
        val store = TrafficJournalStore(storage, { 10_000L })
        store.setEnabled(true)
        store.correlateDns("192.0.2.1", "dns.example", 42, 10_000)
        store.ingest(TrafficJournalStore.Observation(10_000, "192.0.2.1", uid = 42, domain = "host.example",
            domainSource = TrafficJournalStore.DomainSource.HTTP_HOST))
        assertEquals("host.example", store.entries().first().domain)
        store.ingest(TrafficJournalStore.Observation(10_000, "192.0.2.1", uid = 43))
        assertNull(store.entries().first().domain)
        store.correlateDns("192.0.2.1", "other.example", 42, 10_000)
        store.ingest(TrafficJournalStore.Observation(10_000, "192.0.2.1", uid = 42))
        assertNull(store.entries().first().domain)
    }
}
