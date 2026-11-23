package org.mcrf.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdLaunchGateTest {
    @Test fun recreationAndResumeDoNotReplayColdLaunch() {
        val process = ColdLaunchGate()
        assertTrue(process.take(true, true))
        repeat(4) { assertFalse(process.take(true, true)) }
        assertTrue(ColdLaunchGate().take(true, true))
    }

    @Test fun enablingMidProcessWaitsUntilNextColdLaunch() {
        val process = ColdLaunchGate()
        assertFalse(process.take(false, true))
        assertFalse(process.take(true, true))
    }

    @Test fun reducedMotionSkipsEffectForWholeProcess() {
        val process = ColdLaunchGate()
        assertFalse(process.take(true, false))
        assertFalse(process.take(true, true))
    }
}
