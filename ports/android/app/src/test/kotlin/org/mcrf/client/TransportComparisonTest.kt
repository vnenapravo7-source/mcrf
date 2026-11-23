package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class TransportComparisonTest {
    private fun profile(id: String, host: String = "node.public.com", transport: String = "ws") = ServerImport.Server(id,
        "secret-name", "vless", host, 443, JSONObject().put("type", "vless").put("server", host).put("server_port", 443)
            .put("uuid", "secret-credential").put("transport", JSONObject().put("type", transport)), null, null)
    @Test fun requiresDistinctProfilesOnOneServer() {
        val a = profile("a"); val b = profile("b"); val foreign = profile("c", "other.public.com")
        TransportComparisonPlan.validate(a, listOf(a, b))
        for (bad in listOf(listOf(a), listOf(a, a), listOf(a, foreign))) {
            try { TransportComparisonPlan.validate(a, bad); fail("Must reject") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(listOf(a, b), TransportComparisonPlan.candidates(a, listOf(a, b, foreign)))
    }
    @Test fun interleavesRepeatsAndReportsSuccessMedianSpread() {
        val a = profile("a"); val b = profile("b", transport = "grpc")
        val calls = mutableListOf<String>(); var tick = 0L
        val counts = mutableMapOf<String, Int>()
        val runner = TransportComparison(TransportComparisonProvider { entry, _ ->
            calls += entry.id; tick += 100
            val n = counts.getOrDefault(entry.id, 0); counts[entry.id] = n + 1
            if (entry.id == a.id) listOf(30L, 10L, 20L)[n] else listOf(5L, null, 5L)[n]
        }) { tick }
        val report = runner.run(a, listOf(a, b), TransportComparisonCancellation())
        assertEquals(listOf(a.id, b.id, b.id, a.id, a.id, b.id), calls)
        assertTrue(report.complete); assertEquals(1, report.suggestedSlot)
        assertEquals(20L, report.results[0].medianMs); assertEquals(20L, report.results[0].spreadMs)
        assertEquals(2, report.results[1].successes)
    }
    @Test fun budgetExhaustionNeverSuggestsAWinner() {
        val a = profile("a"); val b = profile("b"); var tick = 0L; var calls = 0
        val report = TransportComparison(TransportComparisonProvider { _, _ -> calls++; tick += 120_000; 10L }) { tick }
            .run(a, listOf(a, b), TransportComparisonCancellation())
        assertEquals(1, calls); assertFalse(report.complete); assertTrue(report.exhausted); assertNull(report.suggestedSlot)
    }
    @Test fun cancellingDrainsCurrentAttemptAndStartsNoOtherProbe() {
        val a = profile("a"); val token = TransportComparisonCancellation(); var calls = 0
        try {
            TransportComparison(TransportComparisonProvider { _, _ -> calls++; token.close(); 10L }) { 0L }
                .run(a, listOf(a, profile("b")), token)
            fail("Must cancel")
        } catch (_: CancellationException) { assertEquals(1, calls) }
    }
    @Test fun secretsCannotEnterLabelsOrReportsAndProfilesRemainUntouched() {
        val a = profile("a", transport = "secret-transport"); val b = profile("b")
        val before = a.outbound!!.toString()
        val report = TransportComparison(TransportComparisonProvider { _, _ -> 10L }) { 0L }
            .run(a, listOf(a, b), TransportComparisonCancellation())
        assertFalse(TransportComparisonPlan.label(a, 1).contains("secret")); assertFalse(report.summary().contains("secret"))
        assertEquals(before, a.outbound.toString())
    }
    @Test fun invalidLatencyRejectsAndFailuresAreNotZeroLatency() {
        val a = profile("a"); val b = profile("b")
        try {
            TransportComparison(TransportComparisonProvider { _, _ -> -1L }) { 0L }.run(a, listOf(a, b), TransportComparisonCancellation())
            fail("Must reject")
        } catch (_: IllegalArgumentException) { }
        val report = TransportComparison(TransportComparisonProvider { _, _ -> null }) { 0L }
            .run(a, listOf(a, b), TransportComparisonCancellation())
        assertNull(report.suggestedSlot); assertNull(report.results.first().medianMs)
    }
    @Test fun groupsKeepVpnAndBsSeparateAndExposeOnlyRepeatedAddresses() {
        val a = profile("a"); val b = profile("b", host = "NODE.PUBLIC.COM")
        fun bs(id: String) = ServerImport.Server(id, "BS", "openflux", a.host, 443, null, null, null,
            JSONObject().put("type", "openflux"))
        val x = bs("x"); val y = bs("y")
        val all = listOf(a, b, x, y, profile("single", "other.public.com"), a)
        assertEquals(listOf(listOf(a, b)), TransportComparisonPlan.groups(all, false))
        assertTrue(TransportComparisonPlan.groups(all, true).isEmpty())
        assertFalse(TransportComparisonPlan.supported(x)) // Native gate must not be widened by a UI action.
    }
    @Test fun groupNameUsesFlagAndShortServerNameInsteadOfHost() {
        val a=profile("a")
        val group=listOf(ServerImport.Server(a.original,"🇩🇪 Nexus Frankfurt 09.08 VLESS tcp REALITY",a.protocol,a.host,a.port,a.outbound,null,null),profile("b"))
        assertEquals("🇩🇪 Nexus Frankfurt · 2",TransportComparisonPlan.groupLabel(group))
    }
    @Test fun hidingRequiresACompleteWinnerAndNeverIncludesAnUntestedServer() {
        val a = profile("a"); val b = profile("b")
        val results = listOf(TransportComparisonResult(1, listOf(10L, 10L, 10L)),
            TransportComparisonResult(2, listOf(20L, 20L, 20L)))
        assertEquals(setOf(b.id), TransportComparisonPlan.hideExceptWinner(TransportComparisonReport(results, true), listOf(a, b)))
        assertTrue(TransportComparisonPlan.hideExceptWinner(TransportComparisonReport(results, false), listOf(a, b)).isEmpty())
        assertTrue(TransportComparisonPlan.hideExceptWinner(TransportComparisonReport(results, true), listOf(a)).isEmpty())
        val failed = TransportComparisonReport(listOf(TransportComparisonResult(1, listOf(null, null, null)),
            TransportComparisonResult(2, listOf(null, null, null))), true)
        assertTrue(TransportComparisonPlan.hideExceptWinner(failed, listOf(a, b)).isEmpty())
    }
}
