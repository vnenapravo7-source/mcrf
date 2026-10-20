package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class HomeScrollStateTest {
    @Test fun primaryVisibleMeansNoMiniature() { assertFalse(HomeScrollState.minimized(240, 0, false, 8)); assertFalse(HomeScrollState.minimized(240, 239, false, 8)) }
    @Test fun leavingViewportShowsMiniature() { assertTrue(HomeScrollState.minimized(240, 240, false, 8)); assertTrue(HomeScrollState.minimized(240, 700, false, 8)) }
    @Test fun edgeJitterDoesNotFlash() { assertTrue(HomeScrollState.minimized(240, 235, true, 8)); assertFalse(HomeScrollState.minimized(240, 230, true, 8)) }
    @Test fun notMeasuredAndOverflowAreSafe() { assertFalse(HomeScrollState.minimized(0, 100, true, 8)); assertTrue(HomeScrollState.minimized(Int.MAX_VALUE, Int.MAX_VALUE, true, 8)) }
}
