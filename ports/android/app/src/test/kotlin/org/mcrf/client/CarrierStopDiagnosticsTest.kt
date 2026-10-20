package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierStopDiagnosticsTest {
    @Test fun requestNeverClaimsCompletionAndPreservesActualGrace() {
        val cs=CarrierStopDiagnostics.requested(true,22)
        assertTrue(cs.contains("ещё работает"));assertTrue(cs.contains("22 с"));assertTrue(cs.contains("таймаут не считается успехом"))
        assertTrue(CarrierStopDiagnostics.requested(false,3).contains("уже завершён"))
    }
    @Test fun completionSeparatesForcedExitAndMissingExitCode() {
        val clean=CarrierStopDiagnostics.completed(1234,false,0)
        assertTrue(clean.contains("1234 мс"));assertTrue(clean.contains("завершение=нет"));assertTrue(clean.contains("код=0"))
        val forced=CarrierStopDiagnostics.completed(22000,true,null)
        assertTrue(forced.contains("завершение=да"));assertTrue(forced.contains("код=не получен"))
    }
    @Test fun malformedTimingCannotBecomeMisleadingEvidence() {
        assertThrows(IllegalArgumentException::class.java){CarrierStopDiagnostics.requested(true,0)}
        assertThrows(IllegalArgumentException::class.java){CarrierStopDiagnostics.completed(-1,false,0)}
    }
}
