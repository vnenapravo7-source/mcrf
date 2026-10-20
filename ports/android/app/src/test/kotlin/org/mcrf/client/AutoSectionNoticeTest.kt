package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Queued with42–45 whole P0 batch; no independent execution. */
class AutoSectionNoticeTest {
    @Test fun eachSuccessfulDirectionNotifiesOnceWithoutReplay() {
        val gate=AutoSectionNotice()
        assertTrue(gate.accept(true,1,7,7,true));assertFalse(gate.accept(true,1,7,7,true))
        assertTrue(gate.accept(false,1,7,7,true));assertFalse(gate.accept(false,1,7,7,true))
        assertTrue(gate.accept(true,2,7,7,true));assertFalse(gate.accept(true,1,7,7,true))
    }
    @Test fun failedOrRetiredTransitionsNeverConsumeANotification() {
        val gate=AutoSectionNotice()
        assertFalse(gate.accept(true,1,7,8,true));assertFalse(gate.accept(true,1,8,8,false))
        assertFalse(gate.accept(true,0,8,8,true));assertTrue(gate.accept(true,1,8,8,true))
    }
}
