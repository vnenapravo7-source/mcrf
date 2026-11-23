package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUsageTest {
    @Test fun duplicateOrOlderCounterReadingsCannotDoubleCountEitherDirection() {
        val original = ServerUsage.Counters(sent = 220, received = 70)
        val stale = ServerUsage.Counters(sent = 180, received = 50)
        assertEquals(ServerUsage.Counters(0, 0), stale.delta(original))
        assertEquals(original, stale.highWater(original))
        val current = ServerUsage.Counters(sent = 240, received = 71)
        assertEquals(ServerUsage.Counters(20, 1), current.delta(stale.highWater(original)))
        assertEquals(current, current.delta(null))
    }

    @Test fun aggregateRoutingCountsForOneServerOnlyWhenEveryPayloadRouteUsesIt() {
        val config = JSONObject().put("outbounds", JSONArray()
            .put(JSONObject().put("tag", "chosen").put("type", "vless"))
            .put(JSONObject().put("tag", "direct").put("type", "direct"))
            .put(JSONObject().put("tag", "auto").put("type", "urltest")))
        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("action", "hijack-dns"))
            .put(JSONObject().put("outbound", "chosen"))
        config.put("route", JSONObject().put("final", "chosen").put("rules", rules))

        assertTrue(UsageServerAttribution.permits(config, "chosen"))
        assertFalse(UsageServerAttribution.permits(config, "direct"))
        assertFalse(UsageServerAttribution.permits(config, "auto"))
        assertFalse(UsageServerAttribution.permits(config, "unknown"))
        rules.put(JSONObject().put("action", "route").put("outbound", "direct"))
        assertFalse(UsageServerAttribution.permits(config, "chosen"))
    }

    @Test fun generatedVpnIdentifiesItsMainServerEvenWithARealLoopbackRoute() {
        val config = ProfileConfig.build(
            """{"outbounds":[{"tag":"chosen","type":"vless","server":"example.com","server_port":443}],"route":{"final":"chosen"}}""",
            true,
            ProfileConfig.DEFAULT_PORT
        )

        assertEquals("chosen", UsageServerAttribution.connectedOutput(config))
        assertFalse(UsageServerAttribution.permits(config, "chosen"))
    }

    @Test fun switchedSessionRequiresAnObservedConcreteGroupMember() {
        val config = JSONObject("""{
            "route":{"final":"auto"},
            "outbounds":[
                {"tag":"auto","type":"selector","default":"chosen","outbounds":["chosen","direct"]},
                {"tag":"chosen","type":"vless"},
                {"tag":"direct","type":"direct"},
                {"tag":"unrelated","type":"vless"}
            ]
        }""")

        assertEquals(null, UsageServerAttribution.connectedOutput(config))
        assertEquals(null, UsageServerAttribution.connectedOutput(config, "unrelated"))
        assertEquals(null, UsageServerAttribution.connectedOutput(config, "direct"))
        assertEquals("chosen", UsageServerAttribution.connectedOutput(config, "chosen"))
        assertFalse(UsageServerAttribution.permits(config, "chosen"))
    }

    @Test fun dailyAndWeeklyViewsCountEachObservedDayExactlyOnce() {
        val day = java.time.LocalDate.of(2026, 11, 20).toEpochDay()
        val samples = mapOf(
            day to ServerUsage.Totals(sent = 80, received = 20, sessions = 1),
            day - 6L to ServerUsage.Totals(sent = 10, sessions = 2),
            day - 7L to ServerUsage.Totals(received = 33)
        )
        val daily = ServerUsage.periods(day, false) { samples[it] ?: ServerUsage.Totals() }
        val weekly = ServerUsage.periods(day, true) { samples[it] ?: ServerUsage.Totals() }
        assertEquals(20, daily.size)
        assertEquals(20, weekly.size)
        assertEquals(samples.getValue(day), daily.last().totals)
        assertEquals(ServerUsage.Totals(90, 20, 3), weekly.last().totals)
        assertEquals(ServerUsage.Totals(received = 33), weekly[weekly.lastIndex - 1].totals)
        assertEquals(ServerUsage.Totals(), weekly.first().totals)
    }

    @Test fun updateFillUsesActualBytesAndClampsInvalidProgress() {
        assertEquals(0, AppUpdatePresentation.progressPercent(0, 200))
        assertEquals(29, AppUpdatePresentation.progressPercent(58, 200))
        assertEquals(100, AppUpdatePresentation.progressPercent(200, 200))
        assertEquals(100, AppUpdatePresentation.progressPercent(Long.MAX_VALUE, 200))
        assertEquals(0, AppUpdatePresentation.progressPercent(-1, 200))
        assertEquals(0, AppUpdatePresentation.progressPercent(0, 0))
    }
}
