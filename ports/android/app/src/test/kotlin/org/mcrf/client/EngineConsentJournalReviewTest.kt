package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** New final-batch journal policy cases. Not an Android installer/process-death proof. */
class EngineConsentJournalReviewTest {
    private val token="11111111-2222-3333-4444-555555555555"
    private val other="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private fun entry(stage:EngineConsentJournal.Stage=EngineConsentJournal.Stage.PREPARED)=EngineConsentJournal.Entry(token,stage)

    @Test fun bothDurableStagesRoundTripWithoutPresentationState() {
        for(stage in EngineConsentJournal.Stage.values()) {
            val original=entry(stage)
            assertEquals(original,EngineConsentJournal.parse(original.encode()))
        }
    }
    @Test fun malformedOversizedUnknownAndTrailingJournalValuesAreRejected() {
        for(raw in listOf(null,"",token, "$token|", "$token|prepared", "$token|INSTALLED",
            "$token|PREPARED|", " $token|PREPARED", "${other.uppercase()}|PREPARED",
            "malformed-token|PREPARED", "x".repeat(4096))) {
            assertNull(EngineConsentJournal.parse(raw))
        }
    }
    @Test fun recreatedProcessCanReleaseOnlyItsExactPreparedIntent() {
        assertEquals(EngineConsentJournal.Recovery.RELEASE_PREPARED,
            EngineConsentJournal.recovery(token,entry(),null))
        assertEquals(EngineConsentJournal.Recovery.RETAIN_UNKNOWN,
            EngineConsentJournal.recovery(other,entry(),null))
    }
    @Test fun sameProcessLivePreparationIsNotMisclassifiedAsOutstandingInstaller() {
        assertEquals(EngineConsentJournal.Recovery.LIVE_PREPARATION,
            EngineConsentJournal.recovery(token,entry(),token))
        assertEquals(EngineConsentJournal.Recovery.RELEASE_PREPARED,
            EngineConsentJournal.recovery(token,entry(),other))
    }
    @Test fun launchingWitnessRetainsBarrierEvenWithoutLiveOwnerOrAfterRecreation() {
        for(live in listOf(null,token,other))assertEquals(EngineConsentJournal.Recovery.RETAIN_OUTSTANDING,
            EngineConsentJournal.recovery(token,entry(EngineConsentJournal.Stage.LAUNCHING),live))
    }
    @Test fun stalePreparedJournalCannotReleaseAnotherPendingToken() {
        for(live in listOf(null,token,other))assertEquals(EngineConsentJournal.Recovery.RETAIN_UNKNOWN,
            EngineConsentJournal.recovery(other,entry(),live))
        assertFalse(EngineConsentJournal.mayAbort(other,entry()))
    }
    @Test fun missingOrCorruptLegacyJournalNeverMeansInstallerWasNotLaunched() {
        for(raw in listOf(null,"CONSENT","INSTALLING","$token|UNKNOWN")) {
            val parsed=EngineConsentJournal.parse(raw)
            assertEquals(EngineConsentJournal.Recovery.RETAIN_UNKNOWN,
                EngineConsentJournal.recovery(token,parsed,null))
            assertFalse(EngineConsentJournal.mayAbort(token,parsed))
        }
    }
    @Test fun abortRequiresExactPreparedTokenAndNeverLaunching() {
        assertTrue(EngineConsentJournal.mayAbort(token,entry()))
        assertFalse(EngineConsentJournal.mayAbort(token,entry(EngineConsentJournal.Stage.LAUNCHING)))
        assertFalse(EngineConsentJournal.mayAbort(other,entry()))
        assertFalse(EngineConsentJournal.mayAbort(token,null))
    }
}
