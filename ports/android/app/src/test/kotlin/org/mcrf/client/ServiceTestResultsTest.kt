package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ServiceTestResultsTest {
    @Test fun groupsMeasuredFailuresSeparatelyFromMissingEvidenceWithoutDroppingResults() {
        val request=DiagnosticRequest(ServiceTestCatalog.find("github-site")!!,DiagnosticPath.DIRECT)
        val success=DiagnosticResult.of(request,DiagnosticState.AVAILABLE)
        val failed=DiagnosticResult.of(request,DiagnosticState.ERROR,DiagnosticIssue.TIMEOUT)
        val unmeasured=listOf(
            DiagnosticResult.of(request,DiagnosticState.UNAVAILABLE,DiagnosticIssue.NO_MCRF_PROXY),
            DiagnosticResult.of(request,DiagnosticState.ERROR,DiagnosticIssue.INTERNAL),
            DiagnosticResult.of(request,DiagnosticState.CANCELLED,DiagnosticIssue.CANCELLED),
            DiagnosticResult.of(request,DiagnosticState.UNAVAILABLE,DiagnosticIssue.BUDGET_EXHAUSTED))
        val result=ServiceTestResults.grouped(listOf(success,failed)+unmeasured)
        assertEquals(listOf(success),result[ServiceTestResults.Group.SUCCESS])
        assertEquals(listOf(failed),result[ServiceTestResults.Group.FAILURE])
        assertEquals(unmeasured,result[ServiceTestResults.Group.UNKNOWN])
        assertEquals(6,result.values.sumOf {it.size})
    }
    @Test fun checkAllIncludesUnsupportedTargetsAsExplicitUnknownRatherThanSkippingThem() {
        val selection=ServiceTestsSelection();selection.selectAll()
        assertEquals(ServiceTestCatalog.targets.map {it.id}.toSet(),selection.selected)
        assertTrue("youtube-quic" in selection.selected)
        ServiceTestCatalog.matching("Netflix")
        assertEquals(ServiceTestCatalog.targets.size,selection.selected.size)
    }
}
