package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class TrafficJournalDnsTest {
    private class Memory:LocalDiagnosticStorage {
        var raw=""
        override fun read()=raw
        override fun write(value:String){raw=value}
        override fun delete(){raw=""}
    }
    @Test fun deliveredAnswerIsBoundToQuestionOwnerAndShortTtl() {
        for((type,ip) in listOf(1 to "192.0.2.1",28 to "2001:db8::1")) {
            val query=PacketDnsWire.query("example.org",type,17)
            val answer=PacketDnsWire.reply(query,0,listOf(InetAddress.getByName(ip)))
            val metadata=PacketDnsWire.observedAddresses(query,answer).single()
            assertEquals("example.org",metadata.domain);assertEquals(InetAddress.getByName(ip).hostAddress,metadata.ip)
            assertEquals(5000L,metadata.ttlMs)
            val ttlOffset=PacketDnsWire.question(answer,true).end+6
            val short=answer.clone();repeat(4){short[ttlOffset+it]=0};short[ttlOffset+3]=1
            assertEquals(1000L,PacketDnsWire.observedAddresses(query,short).single().ttlMs)
            short[ttlOffset+3]=0;assertTrue(PacketDnsWire.observedAddresses(query,short).isEmpty())
        }
    }
    @Test fun unrelatedOwnerTruncatedFailureAndCnameDoNotCorrelate() {
        val query=PacketDnsWire.query("example.org",1,17)
        val answer=PacketDnsWire.reply(query,0,listOf(InetAddress.getByName("192.0.2.1")))
        val start=PacketDnsWire.question(answer,true).end
        val unrelated=answer.clone();unrelated[start+1]=20 // Compression points to "org", not the question owner.
        assertTrue(PacketDnsWire.observedAddresses(query,unrelated).isEmpty())
        assertTrue(PacketDnsWire.observedAddresses(query,PacketDnsWire.reply(query,0,truncated=true)).isEmpty())
        assertTrue(PacketDnsWire.observedAddresses(query,PacketDnsWire.reply(query,3)).isEmpty())
        val cname=answer.clone();cname[start+3]=5
        assertTrue(PacketDnsWire.observedAddresses(query,cname).isEmpty())
        val different=PacketDnsWire.query("other.org",1,17)
        try {PacketDnsWire.observedAddresses(different,answer);fail("Unbound reply accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun unknownUidOtherAppAmbiguityAndExpiredEvidenceStayUnknown() {
        var now=10_000L;val journal=TrafficJournalStore(Memory(),{now});journal.setEnabled(true)
        assertFalse(journal.correlateDns("192.0.2.1","example.org",null,now))
        journal.correlateDns("192.0.2.1","example.org",42,now)
        fun record(uid:Int?,at:Long=now):String? {
            journal.ingest(TrafficJournalStore.Observation(at,"192.0.2.1",uid=uid))
            return journal.entries().first().domain
        }
        assertNull(record(null));assertNull(record(43));assertNull(record(42,now-1))
        assertEquals("example.org",record(42))
        journal.correlateDns("192.0.2.1","other.org",42,now);assertNull(record(42))
        now+=5000;assertNull(record(42))
    }
    @Test fun retirementSafeModeAndOptOutDiscardCorrelations() {
        val journal=TrafficJournalStore(Memory(),{1000L});journal.setEnabled(true)
        journal.correlateDns("192.0.2.1","example.org",42,1000)
        journal.clearDnsCorrelations()
        journal.ingest(TrafficJournalStore.Observation(1000,"192.0.2.1",uid=42))
        assertNull(journal.entries().first().domain)
        journal.setEnabled(false);assertFalse(journal.correlateDns("192.0.2.1","example.org",42,1000))
        journal.setEnabled(true);journal.ingest(TrafficJournalStore.Observation(1000,"192.0.2.1",uid=42))
        assertNull(journal.entries().first().domain)
    }
    @Test fun equivalentNumericSpellingsShareDnsEvidenceAndAmbiguity() {
        val journal=TrafficJournalStore(Memory(),{1000L});journal.setEnabled(true)
        journal.correlateDns("2001:db8:0:0:0:0:0:1","example.org",42,1000)
        journal.ingest(TrafficJournalStore.Observation(1000,"2001:DB8::1",uid=42))
        assertEquals("example.org",journal.entries().first().domain)
        journal.correlateDns("2001:db8::1","other.org",42,1000)
        journal.ingest(TrafficJournalStore.Observation(1000,"2001:db8:0:0:0:0:0:1",uid=42))
        assertNull(journal.entries().first().domain)
    }
}
