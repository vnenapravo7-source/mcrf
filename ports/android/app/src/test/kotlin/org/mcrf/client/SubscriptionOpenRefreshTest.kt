package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SubscriptionOpenRefreshTest {
    @Test fun queuedCancellationAndRunningCancellationReleaseAdmissionAtDifferentBoundaries() {
        var releases=0
        val queued=SubscriptionRefreshLease {releases++}
        queued.cancelQueued();assertFalse(queued.enter());queued.finish();assertEquals(1,releases)
        val running=SubscriptionRefreshLease {releases++}
        assertTrue(running.enter());running.cancelQueued();assertEquals(1,releases)
        running.finish();running.finish();running.cancelQueued();assertEquals(2,releases)
    }
    @Test fun manualModeNeverQueuesAndIntervalIsMeasuredFromLastCompletion() {
        assertFalse(SubscriptionOpenRefresh.due(0,0,1000))
        assertTrue(SubscriptionOpenRefresh.due(360,0,1000))
        assertFalse(SubscriptionOpenRefresh.due(30,1000,1_800_999))
        assertTrue(SubscriptionOpenRefresh.due(30,1000,1_801_000))
        assertTrue(SubscriptionOpenRefresh.due(30,2000,1000))
    }
}
