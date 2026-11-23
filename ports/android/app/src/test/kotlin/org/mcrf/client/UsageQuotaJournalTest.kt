package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class UsageQuotaJournalTest {
    private class Memory:LocalDiagnosticStorage {
        var value=""
        override fun read()=value
        override fun write(value:String){this.value=value}
        override fun delete(){value=""}
    }
    @Test fun onlyConsentedConnectionByteDeltasReachAccounting() {
        var bytes=0L;val now=1_000_000L
        val journal=TrafficJournalStore(Memory(),{now},accountBytes={bytes+=requireNotNull(it.bytes)})
        val connection=TrafficJournalStore.Observation(now,"1.2.3.4",uid=10001,app="org.example.call",bytes=17)
        assertFalse(journal.ingest(connection));assertEquals(0L,bytes)
        journal.setEnabled(true)
        assertTrue(journal.ingest(connection.copy(bytes=null)));assertEquals(0L,bytes)
        assertTrue(journal.ingest(connection.copy(event="dns-answer",domain="example.org")));assertEquals(0L,bytes)
        assertTrue(journal.ingest(connection));assertEquals(17L,bytes)
    }
}
