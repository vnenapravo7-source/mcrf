package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class AutoSelectionDeviationTest {
    @Test fun numericChoicesExcludeZeroAndEndAtOneHundredFifty() {
        assertEquals((1..10).map {it*15},AutoSelectionDeviation.milliseconds)
        assertEquals(15,AutoSelectionDeviation.normalize(0));assertEquals(150,AutoSelectionDeviation.normalize(10000))
        assertEquals(50,AutoSelectionDeviation.normalize(50)) // Existing custom value is not silently rewritten.
    }
    @Test fun failureOnlyKeepsHealthyServerButDoesNotBlockFailureRecovery() {
        assertTrue(AutoSelectionDeviation.keepHealthy(true,true))
        assertFalse(AutoSelectionDeviation.keepHealthy(false,true))
        assertFalse(AutoSelectionDeviation.keepHealthy(true,false))
        assertFalse(AutoSelectionDeviation.keepHealthy(false,false))
    }
}
