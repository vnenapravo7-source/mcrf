package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Metadata fixtures only. Not run in this source-only batch. */
class RuleSuggestionsTest {
    private val now=1_000_000L
    private fun entry(ip:String="8.8.8.8",domain:String?="www.wikipedia.org",app:String?="org.example.reader",uid:Int?=1000,
                      event:String="connection",source:TrafficJournalStore.DomainSource=TrafficJournalStore.DomainSource.SNI,at:Long=now)=
        TrafficJournalStore.Entry(at,uid,app,domain,ip,443,TrafficJournalStore.Protocol.TCP,
            TrafficJournalStore.Route.VPN,TrafficJournalStore.Result.ALLOWED,event=event,domainSource=source)
    @Test fun groupsAppExactDomainAndSingleIpWithoutApplyingOrMutating() {
        val input=listOf(entry());val result=RuleSuggestions.collect(input,now)
        assertEquals(setOf(RuleSuggestions.Kind.APP,RuleSuggestions.Kind.DOMAIN,RuleSuggestions.Kind.IP),result.map{it.kind}.toSet())
        assertEquals("DOMAIN,www.wikipedia.org",result.single{it.kind==RuleSuggestions.Kind.DOMAIN}.condition)
        assertEquals("8.8.8.8/32",result.single{it.kind==RuleSuggestions.Kind.IP}.condition)
        assertEquals(input,listOf(entry()));assertTrue(result.all{it.confidence.startsWith("низкая")})
    }
    @Test fun privateLocalDocumentationAndRedactedEventsAreExcludedEntirely() {
        val addresses=listOf("10.0.0.1","127.0.0.1","169.254.1.1","100.64.0.1","172.31.1.1","192.168.1.1",
            "192.0.2.1","198.51.100.1","203.0.113.2","224.0.0.1","::1","fc00::1","fe80::1","2001:db8::1","::ffff:8.8.8.8")
        for(ip in addresses)assertTrue(ip,RuleSuggestions.collect(listOf(entry(ip=ip)),now).isEmpty())
        for(domain in listOf("printer.local","router","host.internal","hidden.wikipedia.org","redacted.invalid","x.home.arpa","example.org"))
            assertTrue(domain,RuleSuggestions.collect(listOf(entry(domain=domain)),now).isEmpty())
    }
    @Test fun dnsAnswersAmbiguousUidAndOldEvidenceDoNotInventAppOrDomain() {
        assertTrue(RuleSuggestions.collect(listOf(entry(event="dns-answer")),now).isEmpty())
        assertTrue(RuleSuggestions.collect(listOf(entry(at=now-TrafficJournalStore.RETENTION_MS-1)),now).isEmpty())
        val unknown=RuleSuggestions.collect(listOf(entry(uid=null)),now)
        assertFalse(unknown.any{it.kind==RuleSuggestions.Kind.APP})
        val ambiguous=entry().copy(packages=listOf("org.example.other"))
        assertFalse(RuleSuggestions.collect(listOf(ambiguous),now).any{it.kind==RuleSuggestions.Kind.APP})
    }
    @Test fun confidenceIsEvidenceNotRouteAdviceAndDnsIsLowConfidence() {
        val repeated=(0..5).map{entry(at=now-it*10_000)}
        assertTrue(RuleSuggestions.collect(repeated,now).single{it.kind==RuleSuggestions.Kind.DOMAIN}.confidence.startsWith("высокая"))
        val dns=RuleSuggestions.collect(listOf(entry(source=TrafficJournalStore.DomainSource.DNS)),now)
        assertTrue(dns.single{it.kind==RuleSuggestions.Kind.DOMAIN}.confidence.startsWith("низкая"))
        assertTrue(dns.all{it.evidence.contains("не рекомендация")})
    }
    private class Memory:LocalDiagnosticStorage {
        var raw="";var writes=0
        override fun read()=raw
        override fun write(value:String){raw=value;writes++}
        override fun delete(){raw=""}
    }
    @Test fun readOnlyApiDoesNotPrunePersistOrEnableRecording() {
        val memory=Memory();var clock=now
        val journal=TrafficJournalStore(memory,{clock});journal.setEnabled(true)
        journal.ingest(TrafficJournalStore.Observation(now,"8.8.8.8",uid=1000,app="org.example.reader",domain="www.wikipedia.org"))
        val writes=memory.writes;val raw=memory.raw
        assertEquals(1,journal.metadataSnapshot().size)
        clock+=TrafficJournalStore.RETENTION_MS+1
        assertTrue(journal.metadataSnapshot().isEmpty());assertEquals(writes,memory.writes);assertEquals(raw,memory.raw)
        journal.setEnabled(false);val disabled=memory.writes
        assertTrue(journal.metadataSnapshot().isEmpty());assertEquals(disabled,memory.writes);assertFalse(journal.enabled())
    }
    @Test fun numericIpValidationNeverTreatsHostnamesAsAddresses() {
        assertNull(RuleSuggestions.publicIp("www.wikipedia.org"));assertNull(RuleSuggestions.publicIp("fe80::1%wlan0"))
        assertNotNull(RuleSuggestions.publicIp("2606:4700:4700::1111"))
    }
}
