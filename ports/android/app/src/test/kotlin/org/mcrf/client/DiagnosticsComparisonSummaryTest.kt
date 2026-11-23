package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsComparisonSummaryTest {
    @Test fun partialCancellationKeepsSuccessAndExplainsEveryUnfinishedPair() {
        val measured = listOf(ServerServiceMeasurement(2, "github-site", DiagnosticState.AVAILABLE, 43))
        val result = DiagnosticsPresentation.comparisonEntries(measured, linkedSetOf(2, 7),
            linkedSetOf("github-site", "netflix-site"), DiagnosticsPresentation.stopReason(DiagnosticIssue.CANCELLED))
        assertEquals(4, result.size)
        assertEquals(1, result.count { it.group == ServiceTestResults.Group.SUCCESS })
        assertEquals(3, result.count { it.group == ServiceTestResults.Group.UNKNOWN })
        assertEquals(4, result.map { it.serverSlot to it.targetId }.distinct().size)
        assertTrue(result.filter { it.group == ServiceTestResults.Group.UNKNOWN }.all { it.reason.contains("отменена") })
    }

    @Test fun unsupportedAndUnmeasuredAreUnknownButMeasuredErrorsFail() {
        val results = listOf(
            ServerServiceMeasurement(1, "github-site", DiagnosticState.AVAILABLE, 20),
            ServerServiceMeasurement(1, "netflix-site", DiagnosticState.ERROR, limitation = ServerComparisonLimit.BUDGET),
            ServerServiceMeasurement(1, "riot-udp", DiagnosticState.UNAVAILABLE, limitation = ServerComparisonLimit.UNSUPPORTED_SERVICE))
        val entries = DiagnosticsPresentation.comparisonEntries(results, setOf(1), results.mapTo(linkedSetOf()) { it.targetId })
        assertEquals(listOf(ServiceTestResults.Group.SUCCESS, ServiceTestResults.Group.FAILURE, ServiceTestResults.Group.UNKNOWN), entries.map { it.group })
        assertEquals("Эту часть нельзя проверить автоматически", entries.last().reason)
    }

    @Test fun inProgressDoesNotInventFailuresForPendingProbes() {
        assertTrue(DiagnosticsPresentation.comparisonEntries(emptyList(), setOf(1, 2), setOf("github-site")).isEmpty())
        val stopped = DiagnosticsPresentation.comparisonEntries(emptyList(), setOf(1, 2), setOf("github-site"),
            DiagnosticsPresentation.stopReason(DiagnosticIssue.TIMEOUT))
        assertEquals(2, stopped.size)
        assertTrue(stopped.all { it.group == ServiceTestResults.Group.UNKNOWN && it.reason.contains("время") })
    }

    @Test fun everyUnknownLimitationHasAnActionableOrSpecificReason() {
        for (limit in ServerComparisonLimit.entries) assertTrue(DiagnosticsPresentation.comparisonReason(limit).isNotBlank())
        assertTrue(DiagnosticsPresentation.comparisonReason(ServerComparisonLimit.ACTIVE_VPN_ISOLATION).contains("VPN"))
        assertTrue(DiagnosticsPresentation.comparisonReason(ServerComparisonLimit.NETWORK).contains("сети"))
    }

    @Test fun internalErrorsRemainUnknownWhileDnsSuccessCannotCertifyVoice() {
        val failed=DiagnosticResult("github-site",DiagnosticPath.CURRENT_MCRF,DiagnosticFamily.AUTOMATIC,DiagnosticState.ERROR,DiagnosticIssue.INTERNAL)
        val dns=DiagnosticResult("discord-voice",DiagnosticPath.CURRENT_MCRF,DiagnosticFamily.AUTOMATIC,DiagnosticState.AVAILABLE,
            elapsedMs=12,dnsScope=DiagnosticDnsScope.CONTROL_DOH_THROUGH_PROXY)
        val rows=DiagnosticsPresentation.comparisonEntries(listOf(serverServiceMeasurement(1,failed),serverServiceMeasurement(1,dns)),
            setOf(1),setOf(failed.targetId,dns.targetId))
        assertEquals(ServiceTestResults.Group.UNKNOWN,rows.first().group)
        assertEquals(DiagnosticState.UNAVAILABLE,rows.first().state)
        assertEquals(ServiceTestResults.Group.SUCCESS,rows.last().group)
        assertTrue(rows.last().reason.contains("Только DNS"))
        assertTrue(rows.last().reason.contains("DNS профиля и работа приложения не проверены"))
        assertFalse(rows.last().reason.contains("Доступен"))
    }

    @Test fun diagnosticsHasStableSettingsParentAndDoesNotReplaceLegacyConfig() {
        assertEquals(ClientSettingsNavigation.BETA, ClientSettingsNavigation.back(ClientSettingsNavigation.DIAGNOSTICS, "Логи", true))
        assertTrue(ClientSettingsNavigation.isSettings(ClientSettingsNavigation.DIAGNOSTICS))
        assertFalse(ClientSettingsNavigation.visible(ClientSettingsNavigation.DIAGNOSTICS, ExperienceMode.SIMPLE))
        assertNotEquals("Диагностика", ClientSettingsNavigation.DIAGNOSTICS)
    }
}
