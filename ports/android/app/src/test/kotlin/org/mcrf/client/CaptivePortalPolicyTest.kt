package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CaptivePortalPolicyTest {
    @Test fun lackOfValidationDoesNotProvePortal() {
        assertEquals(CaptivePortalState.OFFLINE, CaptivePortalPolicy.system(false, false, false))
        assertEquals(CaptivePortalState.UNVALIDATED, CaptivePortalPolicy.system(true, false, false))
        assertEquals(CaptivePortalState.PORTAL, CaptivePortalPolicy.system(true, true, false))
        assertEquals(CaptivePortalState.PORTAL, CaptivePortalPolicy.system(true, true, true))
        assertEquals(CaptivePortalState.INTERNET, CaptivePortalPolicy.system(true, false, true))
    }
    @Test fun explicitProbeDoesNotCallTimeoutOrServerErrorAPortal() {
        assertEquals(CaptivePortalState.INTERNET, CaptivePortalPolicy.probe(204))
        assertEquals(CaptivePortalState.POSSIBLE_PORTAL, CaptivePortalPolicy.probe(302))
        assertEquals(CaptivePortalState.POSSIBLE_PORTAL, CaptivePortalPolicy.probe(200))
        for (code in listOf(-1, 0, 403, 500, 599))
            assertEquals(CaptivePortalState.UNVALIDATED, CaptivePortalPolicy.probe(code))
    }
    @Test fun pauseDoesNotBlameServersOrPersistLoginAddresses() {
        for (state in listOf(CaptivePortalState.OFFLINE, CaptivePortalState.UNVALIDATED,
            CaptivePortalState.PORTAL, CaptivePortalState.POSSIBLE_PORTAL)) assertTrue(CaptivePortalPolicy.pause(state))
        assertFalse(CaptivePortalPolicy.pause(CaptivePortalState.INTERNET))
        assertFalse(CaptivePortalPolicy.pause(CaptivePortalState.UNKNOWN))
        assertTrue(CaptivePortalPolicy.label(CaptivePortalState.OFFLINE).contains("не ошибка"))
    }
}
