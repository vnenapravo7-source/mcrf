package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class UsageQuotaStoreTest {
    private class Memory:LocalDiagnosticStorage {
        var value="";var reject=false
        override fun read()=value
        override fun write(value:String){if(reject)throw IllegalStateException("storage unavailable");this.value=value}
        override fun delete(){value=""}
    }
    private var now=Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
    private fun rule(id:String="quota")=UsageQuotaPolicy.Rule(id,100,UsageQuotaPolicy.Period.DAILY,UsageQuotaPolicy.Action.SWITCH_SERVER,zone="UTC")
    @Test fun optOutDoesNotWriteAndDeleteRevokesConsent() {
        val memory=Memory();val store=UsageQuotaStore(memory,{now})
        store.record(UsageQuotaPolicy.Delta(now,100));assertEquals("",memory.value)
        store.setEnabled(true);store.configure(listOf(rule()));store.record(UsageQuotaPolicy.Delta(now,100))
        assertEquals(1,store.hits().size)
        store.delete();assertFalse(store.enabled());assertTrue(store.rules().isEmpty());assertEquals("",memory.value)
    }
    @Test fun claimsSurviveReloadAndUnrelatedRuleEdits() {
        val memory=Memory();val store=UsageQuotaStore(memory,{now});store.setEnabled(true);store.configure(listOf(rule()))
        store.record(UsageQuotaPolicy.Delta(now,100));assertTrue(store.claim(store.hits().single()))
        val reopened=UsageQuotaStore(memory,{now});assertFalse(reopened.claim(reopened.hits().single()))
        reopened.configure(listOf(rule(),rule("second")))
        assertEquals(100L,reopened.usage(rule()));assertFalse(reopened.claim(reopened.hits().single()))
        now+=UsageQuotaPolicy.DAY;assertTrue(reopened.hits().isEmpty())
    }
    @Test fun manualPinCooldownAndVisitedTargetsSurviveRestart() {
        val memory=Memory();val store=UsageQuotaStore(memory,{now})
        assertFalse(store.admitSwitch("a","b",true));assertTrue(store.admitSwitch("a","b",false))
        val reopened=UsageQuotaStore(memory,{now});assertFalse(reopened.admitSwitch("b","c",false))
        now+=120_000;assertFalse(reopened.admitSwitch("b","a",false));assertTrue(reopened.admitSwitch("b","c",false))
        now-=UsageQuotaPolicy.DAY;assertFalse(reopened.admitSwitch("c","d",false))
    }
    @Test fun failedPersistenceNeverGrantsASwitchOrChangesConsent() {
        val memory=Memory();val store=UsageQuotaStore(memory,{now});memory.reject=true
        try {store.setEnabled(true);fail("failed write")}catch(_:IllegalStateException){}
        assertFalse(store.enabled())
        try {store.admitSwitch("a","b",false);fail("unpersisted admission")}catch(_:IllegalStateException){}
        memory.reject=false;assertTrue(store.admitSwitch("a","b",false))
    }
    @Test fun dailyRetentionIsBoundedAndRulesDoNotLoseBytesWhenBreakdownFills() {
        val memory=Memory();val store=UsageQuotaStore(memory,{now});store.setEnabled(true);store.configure(listOf(rule()))
        repeat(600){index->store.record(UsageQuotaPolicy.Delta(now,1,app="org.example.app$index",domain="host$index.example",attributedOnly=true))}
        store.record(UsageQuotaPolicy.Delta(now,120));store.flush(true)
        assertTrue(JSONObject(memory.value).getJSONArray("rows").length()<=UsageQuotaStore.MAX_ROWS)
        assertEquals(120L,store.hits().single().used)
        assertTrue(store.report().contains("APP: unknown"))
        now+=94*UsageQuotaPolicy.DAY;store.report();store.flush(true)
        assertEquals(0,JSONObject(memory.value).getJSONArray("rows").length())
    }
    @Test fun customEndIsExclusiveAndMetadataDoesNotInflateGlobalUsage() {
        val memory=Memory();val store=UsageQuotaStore(memory,{now});store.setEnabled(true)
        val custom=rule().copy(period=UsageQuotaPolicy.Period.CUSTOM,customStart=now,customEnd=now+1)
        store.configure(listOf(custom));store.record(UsageQuotaPolicy.Delta(now,100,app="org.example.call",attributedOnly=true));assertTrue(store.hits().isEmpty())
        now++;store.record(UsageQuotaPolicy.Delta(now,100));assertTrue(store.hits().isEmpty())
    }
    @Test fun backgroundUpdateBudgetSurvivesReloadAndDoesNotBlockUnconstrainedWork() {
        val memory=Memory();val store=UsageQuotaStore(memory,{now})
        assertTrue(store.admitBackgroundUpdate(0))
        val reopened=UsageQuotaStore(memory,{now});now+=3_600_000
        assertFalse(reopened.admitBackgroundUpdate(86_400_000))
        assertTrue(reopened.admitBackgroundUpdate(0))
        now+=86_400_000;assertTrue(reopened.admitBackgroundUpdate(86_400_000))
    }
    @Test fun clockRollbackCannotReopenQuotaWindows() {
        val memory=Memory();val store=UsageQuotaStore(memory,{now});store.setEnabled(true);store.configure(listOf(rule()))
        store.record(UsageQuotaPolicy.Delta(now,100));assertTrue(store.claim(store.hits().single()))
        now-=UsageQuotaPolicy.DAY;store.record(UsageQuotaPolicy.Delta(now,100));assertTrue(store.hits().isEmpty())
        now+=UsageQuotaPolicy.DAY;assertFalse(store.claim(store.hits().single()))
    }
}
