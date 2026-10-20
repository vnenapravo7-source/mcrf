package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class HomeMetricsTest {
    private val previous=TrafficState.Snapshot(2048,4096,128,256,"fixture",true)
    @Test fun disconnectedNeverShowsPreviousSession(){
        assertEquals("↓ 0 Б/с\n↑ 0 Б/с",TrafficState.homeSpeed(previous,false))
        assertEquals("↓ 0 Б\n↑ 0 Б",TrafficState.homeTraffic(previous,false))
    }
    @Test fun connectedUsesLiveCounters(){
        assertEquals("↓ 256 Б/с\n↑ 128 Б/с",TrafficState.homeSpeed(previous,true))
        assertEquals("↓ 4.0 КБ\n↑ 2.0 КБ",TrafficState.homeTraffic(previous,true))
    }
    @Test fun waitingForStatisticsStartsAtZero(){
        val waiting=TrafficState.Snapshot(0,0,0,0,"fixture",false)
        assertFalse(TrafficState.homeSpeed(waiting,true).contains("—"))
        assertEquals("↓ 0 Б\n↑ 0 Б",TrafficState.homeTraffic(waiting,true))
    }
}
