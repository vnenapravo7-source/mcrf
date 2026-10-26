package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** New common-batch control-flow cases only; not real process/Android acceptance. */
class EngineScriptCleanupReviewTest {
    @Test fun childRetiresBeforeReceiptLeaseFileAndBusyCleanup() {
        val events=mutableListOf<String>()
        EngineScriptCleanup.finish(AutoCloseable {events+="child"}, {events+="receipt"},
            {events+="lease"}, {events+="file"}, {events+="finished"})
        assertEquals(listOf("child","receipt","lease","file","finished"),events)
    }
    @Test fun interruptedRetirementRetriesSameOwnerBeforeReleasingAnything() {
        val events=mutableListOf<String>();var attempts=0
        try {
            Thread.interrupted()
            EngineScriptCleanup.finish(AutoCloseable {
                attempts++;events+="child"
                if(attempts==1)throw InterruptedException("synthetic")
            }, {events+="receipt"}, {events+="lease"}, {events+="file"}, {events+="finished"})
            assertEquals(listOf("child","child","receipt","lease","file","finished"),events)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {Thread.interrupted()}
    }
    @Test fun failedReceiptDoesNotSkipLeaseOrPrivateFileOrBusyReset() {
        val events=mutableListOf<String>();val failure=IllegalStateException("synthetic receipt")
        val caught=assertThrows(IllegalStateException::class.java) {
            EngineScriptCleanup.finish(null, {events+="receipt";throw failure},
                {events+="lease"}, {events+="file"}, {events+="finished"})
        }
        assertSame(failure,caught);assertEquals(listOf("receipt","lease","file","finished"),events)
    }
    @Test fun failedLeaseReleaseStillAttemptsFileAndBusyCleanup() {
        val events=mutableListOf<String>();val failure=IllegalStateException("synthetic lease")
        val caught=assertThrows(IllegalStateException::class.java) {
            EngineScriptCleanup.finish(null, {}, {events+="lease";throw failure},
                {events+="file"}, {events+="finished"})
        }
        assertSame(failure,caught);assertEquals(listOf("lease","file","finished"),events)
    }
    @Test fun failedPrivateFileCleanupStillResetsBusyExactlyOnce() {
        var releases=0;var finished=0;val failure=IllegalStateException("synthetic file")
        val caught=assertThrows(IllegalStateException::class.java) {
            EngineScriptCleanup.finish(null, {}, {releases++}, {throw failure}, {finished++})
        }
        assertSame(failure,caught);assertEquals(1,releases);assertEquals(1,finished)
    }
    @Test fun absentChildStillCompletesFailedLaunchCleanupInOrder() {
        val events=mutableListOf<String>()
        EngineScriptCleanup.finish(null, {events+="receipt"}, {events+="lease"},
            {events+="file"}, {events+="finished"})
        assertEquals(listOf("receipt","lease","file","finished"),events)
    }
    @Test fun cleanupFailuresDoNotReplaceOriginalReceiptFailure() {
        val original=IllegalStateException("synthetic receipt")
        val lease=IllegalStateException("synthetic lease")
        val file=IllegalStateException("synthetic file");var finished=0
        val caught=assertThrows(IllegalStateException::class.java) {
            EngineScriptCleanup.finish(null,{throw original},{throw lease},{throw file},{finished++})
        }
        assertSame(original,caught);assertArrayEquals(arrayOf(lease,file),caught.suppressed)
        assertEquals(1,finished)
    }
}
