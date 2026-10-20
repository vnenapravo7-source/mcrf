package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SubscriptionUpdateRunsTest {
    @Test fun stoppingThenStartingNeverRevivesThePreviousAttempt() {
        val runs=SubscriptionUpdateRuns();val old=runs.begin()
        runs.cancel();val next=runs.begin()
        assertFalse(old.active);assertTrue(next.active)
        assertThrows(InterruptedException::class.java){old.checkActive()}
        next.checkActive()
    }
    @Test fun replacementCancelsOldAttemptEvenWithoutAnEarlierStopCallback() {
        val runs=SubscriptionUpdateRuns();val old=runs.begin();val next=runs.begin()
        assertFalse(old.active);assertTrue(next.active)
    }
    @Test fun lateOldCompletionCannotFinishOrClearNewAttempt() {
        val runs=SubscriptionUpdateRuns();val old=runs.begin();val next=runs.begin();var completed=0
        runs.finish(old){fail("Old callback must not complete a replacement")}
        runs.finish(next){completed++};runs.finish(next){completed++}
        assertEquals(1,completed)
    }
    @Test fun stopBeforePostedCompletionSuppressesIt() {
        val runs=SubscriptionUpdateRuns();val old=runs.begin()
        runs.cancel();runs.cancel()
        runs.finish(old){fail("Stopped job must not call jobFinished")}
    }
    @Test fun interruptionIsObservedWithoutClearingTheThreadFlag() {
        val attempt=SubscriptionUpdateRuns().begin()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java){attempt.checkActive()}
            assertTrue(Thread.currentThread().isInterrupted)
        }finally {Thread.interrupted()}
    }
}
