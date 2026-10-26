package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SubscriptionOwnershipReviewTest {
    @Test fun stopForOldIdDoesNotCancelReplacement() {
        val runs=SubscriptionUpdateRuns();val old=runs.begin(2401);val next=runs.begin(2403)
        assertFalse(runs.cancel(old.jobId));assertTrue(next.active)
        var completed=false;runs.finish(next){completed=true};assertTrue(completed)
    }
    @Test fun matchingStopCancelsOnce() {
        val runs=SubscriptionUpdateRuns();val attempt=runs.begin(2403)
        assertTrue(runs.cancel(2403));assertFalse(attempt.active);assertFalse(runs.cancel(2403))
        runs.finish(attempt){fail("Stopped job must not finish")}
    }
    @Test fun unknownIdLeavesCurrentCompletionIntact() {
        val runs=SubscriptionUpdateRuns();val attempt=runs.begin(2401)
        assertFalse(runs.cancel(9999));var count=0
        runs.finish(attempt){count++};assertEquals(1,count)
    }
    @Test fun admissionIsHeldUntilMainCompletionAndReleasedExactlyOnce() {
        var releases=0;val lease=SubscriptionRefreshLease {releases++}
        val runs=SubscriptionUpdateRuns();val attempt=runs.begin(2401)
        assertTrue(lease.enter());lease.cancelQueued();assertEquals(0,releases)
        try {runs.finish(attempt){assertEquals(0,releases)}}finally {lease.finish()}
        lease.finish();assertEquals(1,releases)
    }
    @Test fun destroyStillCancelsAnyOwner() {
        val runs=SubscriptionUpdateRuns();val attempt=runs.begin(2403)
        runs.cancel();assertFalse(attempt.active);assertFalse(runs.cancel(2403))
    }
}
