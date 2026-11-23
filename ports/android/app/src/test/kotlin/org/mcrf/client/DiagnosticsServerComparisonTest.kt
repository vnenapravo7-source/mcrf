package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsServerComparisonTest {
    @Test fun comparesOnlySelectedSlotsOnIdenticalRealServiceSet() {
        val calls = mutableListOf<Pair<Set<Int>, String>>()
        val comparison = DiagnosticsServerComparison(DiagnosticsComparisonProvider { slots, target, _ ->
            calls.add(slots to target.id)
            slots.map { ServerServiceMeasurement(it, target.id, DiagnosticState.AVAILABLE, 100L + it) }
        })
        val ids = setOf("github-site", "netflix-site")
        val result = comparison.run(setOf(2, 4), ids, DiagnosticCancellation(1000))
        assertTrue(result.complete); assertEquals(4, result.measurements.size)
        assertEquals(listOf(setOf(2),setOf(2),setOf(4),setOf(4)),calls.map {it.first})
        assertEquals(ids, calls.map { it.second }.toSet())
    }
    @Test fun unsupportedPartsNeverInvokeProvider() {
        val comparison = DiagnosticsServerComparison(DiagnosticsComparisonProvider { _, _, _ -> error("must not start provider") })
        val result = comparison.run(setOf(1), setOf("riot-udp"), DiagnosticCancellation(1000))
        assertEquals(ServerComparisonLimit.UNSUPPORTED_SERVICE, result.measurements.single().limitation)
        assertEquals(DiagnosticState.UNAVAILABLE, result.measurements.single().state)
    }
    @Test fun dnsPartsUseTheSelectedServerProvider() {
        val calls = mutableListOf<String>()
        val comparison = DiagnosticsServerComparison(DiagnosticsComparisonProvider { slots, target, _ ->
            calls += target.id
            slots.map { ServerServiceMeasurement(it, target.id, DiagnosticState.AVAILABLE, 12) }
        })
        val result = comparison.run(setOf(1), setOf("discord-voice"), DiagnosticCancellation(1000))
        assertEquals(listOf("discord-voice"), calls)
        assertEquals(DiagnosticState.AVAILABLE, result.measurements.single().state)
    }
    @Test fun cancellationBeforeStartDoesNotLaunchProvider() {
        val token = DiagnosticCancellation(1000); token.cancel()
        try {
            DiagnosticsServerComparison(DiagnosticsComparisonProvider { _, _, _ -> error("must not start") })
                .run(setOf(1), setOf("github-site"), token)
            fail()
        } catch (_: java.util.concurrent.CancellationException) { }
    }
    @Test fun forgedCoverageAndLatencyCannotBeReportedAsSuccess() {
        for (measured in listOf(emptyList(), listOf(ServerServiceMeasurement(1, "github-site", DiagnosticState.AVAILABLE, -1)))) {
            try {
                DiagnosticsServerComparison(DiagnosticsComparisonProvider { _, _, _ -> measured }).run(setOf(1), setOf("github-site"), DiagnosticCancellation(1000))
                fail()
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun everySelectedServerRetiresBeforeTheNextOneIncludingAllServices() {
        val events=mutableListOf<String>()
        val provider=object:DiagnosticsComparisonProvider {
            override fun measure(serverSlots:Set<Int>,target:ServiceTestTarget,token:DiagnosticCancellation):List<ServerServiceMeasurement> {
                events.add("${serverSlots.single()}:${target.id}")
                return listOf(ServerServiceMeasurement(serverSlots.single(),target.id,DiagnosticState.AVAILABLE,1))
            }
            override fun finishServer(){events.add("closed")}
        }
        val all=ServiceTestCatalog.targets.mapTo(linkedSetOf()){it.id}
        val report=DiagnosticsServerComparison(provider).run(linkedSetOf(5,2,8,1,3),all,DiagnosticCancellation(5000))
        assertEquals(all.size*5,report.measurements.size)
        assertEquals(5,events.count {it=="closed"})
        assertEquals(listOf(5,2,8,1,3),events.filter {it!="closed"}.map {it.substringBefore(':').toInt()}.distinct())
        events.chunked(ServiceTestCatalog.targets.count {it.check!=DiagnosticCheck.UNSUPPORTED}+1).forEach {assertEquals("closed",it.last())}
    }

    @Test fun cancellationInsideProviderStillRetiresAndNeverStartsAnotherServer() {
        var calls=0;var closes=0
        val token=DiagnosticCancellation(1000)
        val provider=object:DiagnosticsComparisonProvider {
            override fun measure(serverSlots:Set<Int>,target:ServiceTestTarget,token:DiagnosticCancellation):List<ServerServiceMeasurement> {
                calls++;token.cancel()
                return listOf(ServerServiceMeasurement(serverSlots.single(),target.id,DiagnosticState.AVAILABLE,1))
            }
            override fun finishServer(){closes++}
        }
        try {DiagnosticsServerComparison(provider).run(setOf(1,2),setOf("github-site"),token);fail()}
        catch(_:java.util.concurrent.CancellationException){}
        assertEquals(1,calls);assertEquals(1,closes)
    }

    @Test fun providerFailureOrForgedCoverageCannotSkipRetirement() {
        var closes=0
        val provider=object:DiagnosticsComparisonProvider {
            override fun measure(serverSlots:Set<Int>,target:ServiceTestTarget,token:DiagnosticCancellation)=emptyList<ServerServiceMeasurement>()
            override fun finishServer(){closes++}
        }
        try {DiagnosticsServerComparison(provider).run(setOf(1),setOf("github-site"),DiagnosticCancellation(1000));fail()}
        catch(_:IllegalArgumentException){}
        assertEquals(1,closes)
    }
}
