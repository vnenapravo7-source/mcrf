package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ProbeBackendScopeTest {
    @Test fun noConfiguredCoreOnlyUsesAnActuallyOwnedSteerRuntime() {
        assertFalse(ProbeBackendScope.usesSteer(null,false,false))
        assertTrue(ProbeBackendScope.usesSteer(null,true,false))
        assertFalse(ProbeBackendScope.usesSteer(null,true,true))
    }
    @Test fun fallbackRuntimeDoesNotNeedPreferenceMutation() {
        assertTrue(ProbeBackendScope.usesSteer("singbox",true,false))
    }
    @Test fun unownedUiProbeUsesOnlyConfiguredCore() {
        assertFalse(ProbeBackendScope.usesSteer("singbox",false,false))
        assertTrue(ProbeBackendScope.usesSteer("steer",false,false))
    }
    @Test fun explicitProviderDiagnosticNeverBorrowsSteer() {
        assertFalse(ProbeBackendScope.usesSteer("steer",true,true))
    }
}
