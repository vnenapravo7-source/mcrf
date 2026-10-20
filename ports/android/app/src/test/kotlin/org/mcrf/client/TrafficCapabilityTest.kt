package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class TrafficCapabilityTest {
    @Test fun unsupportedConnectedCountersNeverLookLikeMeasuredZero() {
        val state=TrafficState.Snapshot(0,0,0,0,"WG",false,false)
        assertEquals("↓ —\n↑ —",TrafficState.homeSpeed(state,true))
        assertEquals("↓ —\n↑ —",TrafficState.homeTraffic(state,true))
        assertEquals("Счётчики трафика недоступны",TrafficState.notificationTraffic(state))
        assertEquals("↓ 0 Б\n↑ 0 Б",TrafficState.homeTraffic(state,false))
    }
    @Test fun ordinaryPendingCountersKeepExistingZeroPresentation() {
        val state=TrafficState.Snapshot(0,0,0,0,"pending",false)
        assertTrue(state.countersSupported)
        assertEquals("↓ 0 Б/с\n↑ 0 Б/с",TrafficState.homeSpeed(state,true))
        assertEquals("Трафик за подключение: —",TrafficState.notificationTraffic(state))
    }
    @Test fun capabilitySurvivesRenameAndStopButDoesNotDisableNextCore() {
        val before=TrafficState.current
        try {
            TrafficState.reset("WG",false)
            TrafficState.update(50,100,1000)
            assertFalse(TrafficState.current.available)
            assertEquals(0L,TrafficState.current.sent)
            TrafficState.server("renamed");TrafficState.stop()
            assertFalse(TrafficState.current.countersSupported)
            TrafficState.reset("sing-box")
            TrafficState.update(50,100,2000)
            assertTrue(TrafficState.current.countersSupported)
            assertTrue(TrafficState.current.available)
            assertEquals(50L,TrafficState.current.sent)
        } finally {TrafficState.reset("");TrafficState.current=before}
    }
}
