package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Source-only failure injection for the staging used after Binder death.
 * Not an actual Android Binder/native/process acceptance receipt. */
class PrimaryDeathCleanupTest {
    @Test fun fileCleanupFailureRetainsSlotLeaseAndCompletion() {
        var filesReady=false;var slotHeld=true;var leaseHeld=true;var callbacks=0
        val retirement=ClientPrimaryRetirement(Unit,{}, {callbacks++},{check(filesReady)},
            listOf({leaseHeld=false}),{slotHeld=false})
        try {
            assertFalse(retirement.attempt());assertTrue(slotHeld);assertTrue(leaseHeld);assertFalse(retirement.complete)
            filesReady=true;assertTrue(retirement.attempt());assertFalse(slotHeld);assertFalse(leaseHeld);assertEquals(1,callbacks)
        }finally{filesReady=true;retirement.attempt()}
    }
    @Test fun moduleReleaseFailureCannotBecomeAckViaFinallyAndCompletedStepsDoNotRepeat() {
        var releaseReady=false;var cleanupCalls=0;var releaseCalls=0;var notices=0;var acks=0
        val retirement=ClientPrimaryRetirement(Unit,{}, {},{cleanupCalls++},listOf(
            {releaseCalls++;check(releaseReady)}, {notices++}),{acks++})
        try {
            assertFalse(retirement.attempt());assertEquals(0,acks);assertEquals(0,notices)
            releaseReady=true;assertTrue(retirement.attempt());assertEquals(1,cleanupCalls);assertEquals(2,releaseCalls)
            assertEquals(1,notices);assertEquals(1,acks)
            assertTrue(retirement.attempt());assertEquals(1,acks);assertEquals(2,releaseCalls)
        }finally{releaseReady=true;retirement.attempt()}
    }
    @Test fun interruptedCleanupStaysPendingUntilRealRetryCompletes() {
        val previous=Thread.interrupted();var fail=true;var acks=0
        val retirement=ClientPrimaryRetirement(Unit,{}, {},{if(fail)throw InterruptedException()},emptyList(),{acks++})
        try {
            assertFalse(retirement.attempt());assertTrue(Thread.interrupted());assertEquals(0,acks)
            fail=false;assertTrue(retirement.attempt());assertEquals(1,acks)
        }finally{fail=false;Thread.interrupted();retirement.attempt();if(previous)Thread.currentThread().interrupt()}
    }
}
