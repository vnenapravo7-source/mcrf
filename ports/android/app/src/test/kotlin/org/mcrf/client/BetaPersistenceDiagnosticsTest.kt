package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BetaPersistenceDiagnosticsTest {
    private class Memory : LocalDiagnosticStorage {
        var raw="";var writes=0;var deletes=0
        override fun read()=raw
        override fun write(value:String){raw=value;writes++}
        override fun delete(){raw="";deletes++}
    }
    private fun config(value:String)=ChangeHistoryStore.STORE_NAMES.associateWith {
        if(it=="profile.enc") value else "{}"
    }
    private fun fails(action:()->Unit){try{action();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}

    @Test fun numericIpv6RejectsMalformedCompressionWithoutDnsResolution() {
        val journal=TrafficJournalStore(Memory(),{1000L});journal.setEnabled(true)
        for(ip in listOf("::","::1","2001:db8::1","1:2:3:4:5:6:7:8","1:2:3:4:5:6:7::"))
            assertTrue(ip,journal.ingest(TrafficJournalStore.Observation(1000,ip)))
        for(ip in listOf(":::",":::1","1:::2","1::2::3",":1:2:3:4:5:6:7","1:2:3:4:5:6:7:","1:2:3:4:5:6:7:8::","2001:db8::host","example.com"))
            fails {journal.ingest(TrafficJournalStore.Observation(1000,ip))}
    }

    @Test fun historyCountAndBytesBoundOldestSnapshots() {
        val memory=Memory();var now=1L
        val history=ChangeHistoryStore(memory,{now++},maxCount=3,maxBytes=1500)
        repeat(15){assertTrue(history.checkpoint(config("value-$it")))}
        val summaries=history.summaries(config("current"))
        assertEquals(3,summaries.size);assertTrue(memory.raw.toByteArray().size<=1500)
        assertEquals(15L,summaries.first().timestamp)
        val previous=memory.raw
        assertFalse(history.checkpoint(config("x".repeat(3000))))
        assertEquals(previous,memory.raw)
    }
    @Test fun historyByteBoundCountsUtf8AndJsonEscaping() {
        val memory=Memory();val history=ChangeHistoryStore(memory,maxBytes=900)
        repeat(8){history.checkpoint(config("я\\\"".repeat(30)+it))}
        assertTrue(memory.raw.toByteArray(Charsets.UTF_8).size<=900)
        assertTrue(history.summaries(config("now")).size<8)
    }
    @Test fun historyIgnoresFormattingAndOperationalObservations() {
        val memory=Memory();val history=ChangeHistoryStore(memory)
        val original=config("{\"a\":1,\"b\":2}")+("connections.enc" to """{"servers":[],"subscriptions":[{"updatedAt":1}],"probes":{"a":1}}""")
        val refreshed=original+("profile.enc" to "{ \"b\":2, \"a\":1 }")+
            ("connections.enc" to """{"servers":[],"subscriptions":[{"updatedAt":2}],"probes":{"b":9}}""")
        history.checkpoint(original);history.checkpoint(refreshed)
        assertEquals(1,memory.writes)
        // Operational refreshes have no user-visible history card.
        assertTrue(history.summaries(refreshed).isEmpty())
        history.checkpoint(refreshed+("connections.enc" to """{"servers":["new"],"subscriptions":[]}"""))
        assertEquals(2,memory.writes)
        assertTrue(history.summaries(refreshed).isNotEmpty())
    }
    @Test fun historyPresentationNeverContainsConfigValuesOrCredentials() {
        val memory=Memory();val history=ChangeHistoryStore(memory)
        val secret="vless://fixture-secret@private-host?token=fixture-token"
        history.checkpoint(config(secret))
        val summary=history.summaries(config("different")).single()
        assertEquals(listOf("profile.enc"),summary.changedStores)
        assertFalse(summary.toString().contains("fixture"));assertFalse(summary.toString().contains("private-host"))
    }
    @Test fun restoreRequiresConfirmationAndValidationBeforeCommit() {
        val memory=Memory();val history=ChangeHistoryStore(memory)
        history.checkpoint(config("old"));var current=config("new")
        val request=history.prepareRestore(history.summaries(current).first().id,current)
        assertFalse(history.restore(request,{current},{false},{fail("Cancelled restore validated")},{fail("Cancelled restore committed")}))
        fails {history.restore(request,{current},{true},{throw IllegalArgumentException("invalid")},{current=it})}
        assertEquals("new",current["profile.enc"]);assertEquals(1,memory.writes)
        assertTrue(history.restore(request,{current},{true},{assertEquals("old",it["profile.enc"])},{current=it}))
        assertEquals("old",current["profile.enc"])
        // Exactly one safety snapshot; restore does not recursively append its target.
        assertEquals(2,memory.writes)
        val rollback=history.prepareRestore(history.summaries(current).first().id,current)
        assertTrue(history.restore(rollback,{current},{true},{},{current=it}))
        assertEquals("new",current["profile.enc"])
    }
    @Test fun restoreRejectsChangedCurrentAndFailedAtomicCommitPreservesCurrent() {
        val memory=Memory();val history=ChangeHistoryStore(memory)
        history.checkpoint(config("old"));var current=config("new")
        val id=history.summaries(current).single().id
        val stale=history.prepareRestore(id,current);current=config("changed")
        fails {history.restore(stale,{current},{true},{},{fail("Stale approval committed")})}
        val fresh=history.prepareRestore(id,current)
        fails {history.restore(fresh,{current},{true},{},{throw IllegalStateException("atomic commit failed")})}
        assertEquals("changed",current["profile.enc"])
    }
    @Test fun evictedApprovalCannotRestoreAndHistoryDeleteClearsOnlyHistory() {
        val memory=Memory();val history=ChangeHistoryStore(memory,maxCount=1)
        history.checkpoint(config("old"));val request=history.prepareRestore(history.summaries(config("current")).single().id,config("current"))
        history.checkpoint(config("next"))
        fails {history.restore(request,{config("current")},{true},{},{fail("Evicted snapshot committed")})}
        history.delete();assertEquals(1,memory.deletes);assertTrue(history.summaries(config("current")).isEmpty())
    }
    @Test fun journalDefaultDisabledAndDisabledIngestionNeverWrites() {
        val memory=Memory();val journal=TrafficJournalStore(memory,{1000L})
        assertFalse(journal.enabled());assertFalse(journal.ingest(TrafficJournalStore.Observation(-1,"not an IP")))
        assertFalse(journal.correlateDns("not an IP","secret://value",null,0))
        assertTrue(journal.entries().isEmpty());assertEquals(0,memory.writes);assertEquals("",memory.raw)
        journal.setEnabled(true);journal.setEnabled(false);val writes=memory.writes
        assertFalse(journal.ingest(TrafficJournalStore.Observation(1000,"192.0.2.1")))
        assertEquals(writes,memory.writes)
    }
    @Test fun journalUnknownAttributionIsExplicitInDisplayAndExport() {
        val memory=Memory();val journal=TrafficJournalStore(memory,{1000L});journal.setEnabled(true)
        journal.ingest(TrafficJournalStore.Observation(1000,"192.0.2.1",app="org.example.app"))
        val entry=journal.entries().single()
        assertNull(entry.uid);assertNull(entry.app);assertNull(entry.domain)
        assertTrue(entry.attribution.contains("UID: неизвестен"));assertTrue(entry.attribution.contains("домен: неизвестен"))
        val exported=journal.export()
        assertTrue(exported.contains("unknown UID"));assertTrue(exported.contains("unknown domain"))
        val row=JSONObject(exported).getJSONArray("entries").getJSONObject(0)
        assertTrue(row.isNull("uid"));assertEquals("OTHER",row.getString("protocol"));assertEquals("UNKNOWN",row.getString("route"))
    }
    @Test fun dnsCorrelationRequiresSameUidUnexpiredAndUnambiguousEvidence() {
        val memory=Memory();var now=10_000L;val journal=TrafficJournalStore(memory,{now});journal.setEnabled(true)
        journal.correlateDns("192.0.2.1","example.org",42,now,1000)
        journal.ingest(TrafficJournalStore.Observation(now,"192.0.2.1",uid=42,app="org.example.app"))
        assertEquals("example.org",journal.entries().first().domain)
        assertEquals("org.example.app",journal.entries().first().app)
        journal.ingest(TrafficJournalStore.Observation(now,"192.0.2.1",uid=43))
        assertNull(journal.entries().first().domain)
        journal.correlateDns("192.0.2.1","other.org",42,now,1000)
        journal.ingest(TrafficJournalStore.Observation(now,"192.0.2.1",uid=42));assertNull(journal.entries().first().domain)
        now+=1001;journal.ingest(TrafficJournalStore.Observation(now,"192.0.2.1",uid=42));assertNull(journal.entries().first().domain)
    }
    @Test fun journalBoundsCountBytesAndAgeAndPersistsConsentAcrossInstances() {
        val memory=Memory();var now=1000L
        val journal=TrafficJournalStore(memory,{now},maxCount=3,maxBytes=850,retentionMs=100)
        journal.setEnabled(true)
        repeat(20){now++;journal.ingest(TrafficJournalStore.Observation(now,"192.0.2.1",protocol=TrafficJournalStore.Protocol.TCP,route=TrafficJournalStore.Route.VPN,result=TrafficJournalStore.Result.ALLOWED))}
        assertTrue(journal.entries().size in 1..3);assertTrue(memory.raw.toByteArray().size<=850)
        assertTrue(TrafficJournalStore(memory,{now},maxCount=3,maxBytes=850,retentionMs=100).enabled())
        now+=101;assertTrue(journal.entries().isEmpty())
        assertEquals(0,JSONObject(memory.raw).getJSONArray("entries").length())
    }
    @Test fun journalExportDeleteAndReenableDoNotResurrectDnsOrEntries() {
        val memory=Memory();val journal=TrafficJournalStore(memory,{1000L});journal.setEnabled(true)
        journal.correlateDns("2001:db8::1","example.org",42,1000)
        journal.ingest(TrafficJournalStore.Observation(1000,"2001:db8::1",uid=42,protocol=TrafficJournalStore.Protocol.UDP,route=TrafficJournalStore.Route.BLOCK,result=TrafficJournalStore.Result.BLOCKED))
        assertEquals("example.org",JSONObject(journal.export()).getJSONArray("entries").getJSONObject(0).getString("domain"))
        journal.delete();assertEquals("",memory.raw);assertFalse(journal.enabled());assertTrue(journal.entries().isEmpty())
        val writes=memory.writes;assertFalse(journal.ingest(TrafficJournalStore.Observation(1000,"192.0.2.1")));assertEquals(writes,memory.writes)
        journal.setEnabled(true);journal.ingest(TrafficJournalStore.Observation(1000,"2001:db8::1"))
        assertNull(journal.entries().single().domain)
    }
    @Test fun ingestionRejectsUrlsHostnamesPayloadAndFutureEvidence() {
        val memory=Memory();val journal=TrafficJournalStore(memory,{1000L});journal.setEnabled(true)
        listOf("private.example","https://192.0.2.1?token=secret","999.0.0.1",":::").forEach {ip->fails {journal.ingest(TrafficJournalStore.Observation(1000,ip))}}
        fails {journal.correlateDns("192.0.2.1","https://secret/path",null,1000)}
        fails {journal.ingest(TrafficJournalStore.Observation(1001,"192.0.2.1"))}
        assertTrue(journal.entries().isEmpty())
    }
    @Test fun concurrentIngestionSerializesAndRemainsBounded() {
        val memory=Memory();val journal=TrafficJournalStore(memory,{1000L},maxCount=20)
        journal.setEnabled(true);val executor=Executors.newFixedThreadPool(4)
        val futures=(0 until 80).map {executor.submit<Boolean> {journal.ingest(TrafficJournalStore.Observation(1000,"192.0.2.${it+1}"))}}
        executor.shutdown();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));futures.forEach {assertTrue(it.get())}
        assertEquals(20,journal.entries().size);assertEquals(81,memory.writes)
    }
    @Test fun corruptDataFailsClosedInsteadOfResettingConsentOrHistory() {
        for(corrupt in listOf("broken","{\"version\":1}","{\"version\":1,\"entries\":false}")){
            val memory=Memory().apply {raw=corrupt}
            val journal=TrafficJournalStore(memory)
            assertThrows(IllegalStateException::class.java){journal.ingest(TrafficJournalStore.Observation(1,"192.0.2.1"))}
            assertThrows(IllegalStateException::class.java){journal.enabled()}
            assertThrows(IllegalStateException::class.java){journal.setEnabled(true)}
            assertThrows(IllegalStateException::class.java){ChangeHistoryStore(memory).checkpoint(config("config"))}
            assertEquals(0,memory.writes);assertEquals(0,memory.deletes);assertEquals(corrupt,memory.raw)
        }
    }
    @Test fun diagnosticsHaveFiveAreasEvidenceTimesAndHonestConfigurationStatus() {
        LocalDiagnostics.network(true,false,true,1000);LocalDiagnostics.serviceStatus("VPN подключён · password=fixture-secret",1000)
        ConnectionDiagnostics.summary(JSONObject("""{"dns":{"servers":[{"type":"https","server":"private-host"}]},"route":{"rules":[]}}"""))
        val rows=LocalDiagnostics.snapshot()
        assertEquals(5,rows.size);assertTrue(rows.all {it.timestamp!=null})
        val dns=rows.single {it.area==LocalDiagnostics.Area.DNS}
        assertEquals(LocalDiagnostics.State.CONFIGURED,dns.state);assertTrue(dns.message.contains("не проверены"))
        assertTrue(rows.first().stale(1000+LocalDiagnostics.FRESH_MS+1))
        assertFalse(rows.toString().contains("fixture-secret"));assertFalse(rows.toString().contains("private-host"))
        LocalDiagnostics.network(null,null,null,2000)
        assertEquals(LocalDiagnostics.State.UNKNOWN,LocalDiagnostics.snapshot().first().state)
    }
    @Test fun eventRedactionCoversStructuredCredentialsAndProfileLinks() {
        val input="""{"password":"fixture password","private_key":"fixture-key","token":"fixture-token"} vless://fixture-id@host https://user:pass@host/path?secret=value"""
        val clean=ClientEvents.redact(input)
        listOf("fixture password","fixture-key","fixture-token","fixture-id","user:pass","secret=value").forEach {assertFalse(it,clean.contains(it))}
    }
}
