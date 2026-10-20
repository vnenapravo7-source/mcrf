package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class PingStatusTest {
    @Test fun zeroAndBoundaryAreGood() { assertEquals(PingStatus.Tone.GOOD, PingStatus.tone(0)); assertEquals(PingStatus.Tone.GOOD, PingStatus.tone(100)) }
    @Test fun higherIsYellowNotFailure() { assertEquals(PingStatus.Tone.HIGH, PingStatus.tone(101)); assertEquals("999 мс", PingStatus.label(999)) }
    @Test fun fastestClusterIsGreenWhenEveryMeasuredServerIsAboveOneHundred() {
        val limit=PingStatus.goodLimit(listOf(null,-1,114,120,150,164,165,2776))
        assertEquals(164,limit);assertEquals(PingStatus.Tone.GOOD,PingStatus.tone(114,limit));assertEquals(PingStatus.Tone.GOOD,PingStatus.tone(164,limit));assertEquals(PingStatus.Tone.HIGH,PingStatus.tone(165,limit));assertEquals(PingStatus.Tone.UNAVAILABLE,PingStatus.tone(-1,limit))
    }
    @Test fun noSuccessDoesNotInventAGreenResultAndLimitCannotOverflow() {
        assertEquals(100,PingStatus.goodLimit(listOf(null,-1)))
        assertEquals(Long.MAX_VALUE,PingStatus.goodLimit(listOf(Long.MAX_VALUE)))
    }
    @Test fun unavailableIsRedAndNamed() { assertEquals(PingStatus.Tone.UNAVAILABLE, PingStatus.tone(-1)); assertEquals("Нет связи", PingStatus.label(-1)) }
    @Test fun unprobedIsNeutral() { assertEquals(PingStatus.Tone.UNKNOWN, PingStatus.tone(null)); assertEquals("—", PingStatus.label(null)) }
    @Test fun orderIsNumericStableAndFailuresAreLast() {
        val delays = mapOf("failed" to -1L,"slow" to 220L,"fast" to 5L,"zero" to 0L,"tie" to 5L,"failed2" to -2L)
        assertEquals(listOf("zero","fast","tie","slow","unknown","failed","failed2"), PingStatus.sorted(listOf("failed","unknown","slow","fast","zero","tie","failed2")) { delays[it] })
    }
    @Test fun notificationHasCombinedTrafficAndNoSpeed() {
        val state = TrafficState.Snapshot(1024, 2048, 99, 88, "Test", true)
        assertEquals("Трафик за подключение: 3.0 КБ", TrafficState.notificationTraffic(state))
        assertFalse(TrafficState.notificationTraffic(state).contains("/с"))
    }
    @Test fun missingCounterIsNotInvented() { assertEquals("Трафик за подключение: —", TrafficState.notificationTraffic(TrafficState.Snapshot(0,0,0,0,"",false))) }
    @Test fun outsideRussiaIsAnExplicitEditableRule() {
        val rule = RouteRules.outsideRussia("ru\n1.2.3.0/24")
        assertEquals("Всё кроме РФ", rule.getString("name")); assertEquals("proxy", rule.getString("target")); assertTrue(rule.getBoolean("invert"))
        assertTrue(RouteRules.matches(rule).getJSONObject(0).getBoolean("invert"))
    }
}
