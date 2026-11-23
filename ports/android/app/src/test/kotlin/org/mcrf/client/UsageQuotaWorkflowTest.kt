package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Exercises the form's amount conversion through the real encrypted-storage contract substitute. */
class UsageQuotaWorkflowTest {
    private class Disk:LocalDiagnosticStorage {
        var data="";var ignore=false
        override fun read()=data
        override fun write(value:String){if(!ignore)data=value}
        override fun delete(){data=""}
    }
    @Test fun severalLimitsRemainIndependentAcrossAddDeleteAndReload() {
        val now=Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        val disk=Disk();var store=UsageQuotaStore(disk,{now})
        val small=UsageQuotaPolicy.Rule("small",100,UsageQuotaPolicy.Period.DAILY,zone="UTC")
        val large=UsageQuotaPolicy.Rule("large",200,UsageQuotaPolicy.Period.DAILY,zone="UTC")
        store.configure(listOf(small));store.setEnabled(true)
        store.record(UsageQuotaPolicy.Delta(now,100));store.flush(true)
        store.configure(store.rules()+large)
        assertEquals(100L,store.usage(small))
        store.record(UsageQuotaPolicy.Delta(now,200));store.flush(true)
        store=UsageQuotaStore(disk,{now})
        assertEquals(listOf(small,large),store.rules())
        assertEquals(300L,store.usage(small));assertEquals(200L,store.usage(large))
        assertEquals(setOf("small","large"),store.hits().map {it.rule.id}.toSet())
        store.configure(store.rules().filter {it.id!="small"})
        store=UsageQuotaStore(disk,{now})
        assertEquals(listOf(large),store.rules());assertEquals(200L,store.usage(large))
    }
    @Test fun convertsMegabytesGigabytesAndDecimalCommaWithoutRounding() {
        assertEquals(1_000_000L,UsageQuotaAmount.bytes("1",UsageQuotaAmount.Unit.MB))
        assertEquals(1_000_000_000L,UsageQuotaAmount.bytes("1",UsageQuotaAmount.Unit.GB))
        assertEquals(1_500_000_000L,UsageQuotaAmount.bytes("1,5",UsageQuotaAmount.Unit.GB))
        assertEquals(250_000L,UsageQuotaAmount.bytes("0.25",UsageQuotaAmount.Unit.MB))
        assertEquals(UsageQuotaAmount.bytes("1000",UsageQuotaAmount.Unit.MB),UsageQuotaAmount.bytes("1",UsageQuotaAmount.Unit.GB))
    }
    @Test fun invalidOrOverflowingAmountCannotBecomeALimit() {
        for(value in listOf("","0","-1","NaN","99999999999999999999999999","0.0000000001")) {
            assertTrue(value,runCatching {UsageQuotaAmount.bytes(value,UsageQuotaAmount.Unit.MB)}.isFailure)
        }
    }
    @Test fun offOnBetweenSamplesDoesNotChargeDisabledTraffic() {
        val disk=Disk();val store=UsageQuotaStore(disk);val meter=UsageQuotaPolicy.Meter()
        store.setEnabled(true)
        val first=store.accountingState()
        assertEquals(100L,meter.sampleAccounting("session",100,0,first.first,first.second))
        store.setEnabled(false);store.setEnabled(true)
        val reopened=UsageQuotaStore(disk).accountingState()
        assertNotEquals(first.second,reopened.second)
        assertEquals(0L,meter.sampleAccounting("session",10000,0,reopened.first,reopened.second))
        assertEquals(25L,meter.sampleAccounting("session",10025,0,reopened.first,reopened.second))
    }
    @Test fun staleAccountingSampleAndUnpersistedClaimCannotTriggerAnAction() {
        val now=Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        val disk=Disk();val store=UsageQuotaStore(disk,{now})
        store.configure(listOf(UsageQuotaPolicy.Rule("quota",10,UsageQuotaPolicy.Period.DAILY,zone="UTC")))
        store.setEnabled(true);val oldEpoch=store.accountingState().second
        store.setEnabled(false);store.setEnabled(true)
        store.record(UsageQuotaPolicy.Delta(now,100),oldEpoch)
        assertTrue(store.hits().isEmpty())
        store.record(UsageQuotaPolicy.Delta(now,10),store.accountingState().second)
        val hit=store.hits().single();disk.ignore=true
        assertThrows(IllegalStateException::class.java){store.claim(hit)}
        disk.ignore=false
        assertTrue(store.claim(hit));assertFalse(store.claim(hit))
    }
    @Test fun eachChosenActionTriggersAtExactThresholdAndSurvivesReloadWithoutDuplicateAdmission() {
        val now=Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        for(unit in UsageQuotaAmount.Unit.entries)for(action in UsageQuotaPolicy.Action.entries) {
            val disk=Disk();var store=UsageQuotaStore(disk,{now})
            val threshold=UsageQuotaAmount.bytes("2",unit)
            val rule=UsageQuotaPolicy.Rule("quota",threshold,UsageQuotaPolicy.Period.DAILY,action,zone="UTC")
            store.configure(listOf(rule));store.setEnabled(true)
            store.record(UsageQuotaPolicy.Delta(now,threshold-1));assertTrue(store.hits().isEmpty())
            store.flush(true);store=UsageQuotaStore(disk,{now})
            assertEquals(listOf(rule),store.rules());assertEquals(threshold-1,store.usage(rule))
            store.record(UsageQuotaPolicy.Delta(now,1))
            val hit=store.hits().single()
            assertEquals(threshold,hit.used);assertEquals(action,hit.rule.action)
            assertTrue(store.claim(hit))
            val reopened=UsageQuotaStore(disk,{now})
            assertTrue(reopened.enabled());assertEquals(rule,reopened.rules().single())
            assertEquals(action,reopened.hits().single().rule.action)
            assertEquals(threshold,reopened.hits().single().used)
            assertFalse(reopened.claim(reopened.hits().single()))
        }
    }
}
