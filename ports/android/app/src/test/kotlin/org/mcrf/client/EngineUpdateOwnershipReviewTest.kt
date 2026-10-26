package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class EngineUpdateOwnershipReviewTest {
    @Test fun lateScheduledStopCannotCancelLaterManualDownload() {
        val scheduled=EngineUpdateRun();scheduled.finish(false)
        val manual=EngineUpdateRun();scheduled.cancel()
        assertTrue(manual.active);assertFalse(manual.cancelled)
    }
    @Test fun completionDoesNotWaitForUnrelatedWork() {
        val scheduled=EngineUpdateRun();scheduled.finish(false)
        val manual=EngineUpdateRun()
        assertFalse(scheduled.active);assertTrue(manual.active)
    }
    @Test fun retryResultBelongsToCompletedRun() {
        val scheduled=EngineUpdateRun();scheduled.finish(true)
        val manual=EngineUpdateRun();manual.finish(false)
        assertTrue(scheduled.retry);assertFalse(manual.retry)
    }
    @Test fun cancellationRetainsAdmissionUntilWorkerActuallyFinishes() {
        val attempt=EngineUpdateRun();attempt.cancel()
        assertTrue(attempt.active);assertTrue(attempt.cancelled)
        attempt.finish(true);assertFalse(attempt.active)
    }
}
