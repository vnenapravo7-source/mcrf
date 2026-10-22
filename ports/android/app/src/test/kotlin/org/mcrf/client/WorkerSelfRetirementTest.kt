package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class WorkerSelfRetirementTest {
    @Test fun liveOwnerCannotHoldStoppedWorkerForever(){
        assertFalse(WorkerSelfRetirement.shouldTerminate(4999,true,true))
        assertTrue(WorkerSelfRetirement.shouldTerminate(5000,true,true))
    }
    @Test fun workingNativeProcessIsNeverRetired(){
        assertFalse(WorkerSelfRetirement.shouldTerminate(60000,false,true))
    }
    @Test fun orphanStillRetiresAfterDeadline(){
        assertFalse(WorkerSelfRetirement.shouldTerminate(4999,false,false))
        assertTrue(WorkerSelfRetirement.shouldTerminate(5000,false,false))
    }
    @Test fun earlyInterruptDoesNotShortenGrace(){
        assertEquals(5000L,WorkerSelfRetirement.remaining(0))
        assertEquals(4001L,WorkerSelfRetirement.remaining(999))
        assertFalse(WorkerSelfRetirement.shouldTerminate(999,true,true))
    }
    @Test fun remainingIsBounded(){
        assertEquals(5000L,WorkerSelfRetirement.remaining(-1))
        assertEquals(0L,WorkerSelfRetirement.remaining(5000))
        assertEquals(0L,WorkerSelfRetirement.remaining(60000))
    }
}
