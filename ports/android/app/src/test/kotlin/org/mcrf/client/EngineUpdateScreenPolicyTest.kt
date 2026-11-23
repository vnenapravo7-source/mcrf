package org.mcrf.client

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class EngineUpdateScreenPolicyTest {
    @Test fun popularExtrasLeadWithoutDroppingOrInventingCatalogueEntries() {
        val previous=listOf("dnstt","csqtt20","tgws","bs","warpscout","wdtt","xray","dnstt")
        assertEquals(listOf("bs","wdtt","tgws","xray","dnstt","csqtt20","warpscout"),EngineUpdateScreenPolicy.additional(previous))
        assertTrue(EngineUpdateScreenPolicy.additional(emptyList()).isEmpty())
        assertEquals(previous.toSet(),EngineUpdateScreenPolicy.additional(previous).toSet())
    }
    @Test fun everyLegacyCombinationRequiresVerifiedReplacementBeforeRemoval() {
        val kinds=EngineUpdateScreenPolicy.legacyKinds.toList()
        for(mask in 0 until (1 shl kinds.size)) {
            val installed=kinds.filterIndexed {index,_->mask and (1 shl index)!=0}
            assertEquals(if(mask==0)EngineUpdateScreenPolicy.Migration.NONE else EngineUpdateScreenPolicy.Migration.INSTALL,
                EngineUpdateScreenPolicy.migration(installed,false))
            assertEquals(if(mask==0)EngineUpdateScreenPolicy.Migration.NONE else EngineUpdateScreenPolicy.Migration.REMOVE,
                EngineUpdateScreenPolicy.migration(installed,true))
        }
    }
    @Test fun wdttAndPrimaryCoresNeverBecomeLegacyRemovalTargets() {
        val unrelated=listOf("wdtt","bs","openflux","steer","sing-box")
        assertTrue(unrelated.none {it in EngineUpdateScreenPolicy.legacyKinds})
        assertEquals(EngineUpdateScreenPolicy.Migration.NONE,EngineUpdateScreenPolicy.migration(unrelated,true))
    }
    @Test fun migrationCannotBeDeclinedWithAnyCsqttGenerationOrQwdttServer() {
        EngineUpdateScreenPolicy.legacyKinds.forEach {kind->
            assertFalse(kind,EngineUpdateScreenPolicy.canDecline(listOf("vless",kind,"wdtt")))
        }
        assertFalse(EngineUpdateScreenPolicy.canDecline(null))
        assertTrue(EngineUpdateScreenPolicy.canDecline(emptyList()))
        assertTrue(EngineUpdateScreenPolicy.canDecline(listOf("wdtt","vless","openflux")))
    }
    @Test fun rejectedBusyLaunchCanRetryButResumeCannotStartAnotherAcceptedCheck() {
        val launch=EngineUpdateProcessLaunch();var checks=0
        assertFalse(launch.start {checks++;false})
        assertTrue(launch.start {checks++;true})
        repeat(10){assertTrue(launch.start {checks++;true})}
        assertEquals(2,checks)
    }
    @Test fun aNewProcessChecksAgainAndExceptionDoesNotLatchTheOldAttempt() {
        val launch=EngineUpdateProcessLaunch()
        try {launch.start {throw IllegalStateException("unavailable")};fail()}catch(_:IllegalStateException){}
        assertTrue(launch.start {true})
        var called=false
        assertTrue(EngineUpdateProcessLaunch().start {called=true;true})
        assertTrue(called)
    }
    @Test fun racingLaunchHooksAcceptOnlyOneCheck() {
        val launch=EngineUpdateProcessLaunch();val calls=AtomicInteger()
        val workers=Executors.newFixedThreadPool(4)
        try {
            val results=workers.invokeAll((1..16).map {Callable {launch.start {calls.incrementAndGet();true}}})
            assertTrue(results.all {it.get()});assertEquals(1,calls.get())
        }finally {workers.shutdownNow()}
    }
    @Test fun summariesExposeMainLimitsAndBsHelpListsSupportedGenerations() {
        assertEquals("WL_CSQTT_QWDTT",EngineUpdateScreenPolicy.BS_LABEL)
        listOf("QWDTT","2.0","2.1.5","2.1.9","2.5.1","2.5.2","2.5.3","WDTT").forEach {
            assertTrue(it,EngineUpdateScreenPolicy.BS_HELP.contains(it))
        }
        assertTrue(EngineUpdateScreenPolicy.primarySummary("sing-box").contains("IPv6"))
        assertTrue(EngineUpdateScreenPolicy.primarySummary("steer").contains("Без локального прокси"))
    }
}
