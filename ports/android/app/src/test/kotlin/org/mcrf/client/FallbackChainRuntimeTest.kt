package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class FallbackChainRuntimeTest {
    @Test fun twoIndependentSamplesAreRequiredAndSuccessClearsEvidence() {
        val runtime = FallbackChainRuntime()
        assertTrue(runtime.due(0)); assertFalse(runtime.due(14_999)); assertTrue(runtime.due(15_000))
        assertFalse(runtime.report("server", 1, false))
        assertFalse(runtime.report("server", 1, true))
        assertFalse(runtime.report("server", 1, false))
        assertTrue(runtime.report("server", 1, false))
    }
    @Test fun networkServerOrPortalChangeCannotReuseOldFailureEvidence() {
        val runtime = FallbackChainRuntime()
        assertFalse(runtime.report("first", 1, false))
        assertFalse(runtime.report("first", 2, false))
        assertFalse(runtime.report("second", 2, false))
        runtime.clearEvidence()
        assertFalse(runtime.report("second", 2, false))
    }
}
