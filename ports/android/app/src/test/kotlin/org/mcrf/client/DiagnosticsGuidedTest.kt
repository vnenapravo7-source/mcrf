package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsGuidedTest {
    private val target = ServiceTestCatalog.find("github-site")!!
    private fun env() = DiagnosticsEnvironment(true, false, true, null, true, true, false, true, null,
        DiagnosticRouteEvidence(DiagnosticRoutePolicy.UNKNOWN), false)
    @Test fun orderedLayersDoNotPromoteAProxyResponseToSelectedServerProof() {
        val reports = mutableListOf<GuidedDiagnosticsReport>()
        val pipeline = DiagnosticsGuided({ env() }, ServiceProbe { request, _ -> DiagnosticResult.of(request, DiagnosticState.AVAILABLE) }, null)
        val result = pipeline.run(target, DiagnosticCancellation(1000), reports::add)
        assertTrue(result.complete); assertEquals(DiagnosticStep.values().toList(), result.findings.map { it.step })
        val server = result.findings.first { it.step == DiagnosticStep.SERVER }
        assertEquals(DiagnosticState.UNAVAILABLE, server.state); assertEquals(DiagnosticConfidence.UNKNOWN, server.confidence)
        assertTrue(reports.all { !it.complete })
    }
    @Test fun portalIsObservedButUnvalidatedInternetIsNotAServerFailure() {
        val direct = DiagnosticResult.of(DiagnosticRequest(target, DiagnosticPath.DIRECT), DiagnosticState.UNAVAILABLE, DiagnosticIssue.NO_DIRECT_NETWORK)
        assertEquals(DiagnosticCause.PORTAL_REPORTED, DiagnosticsGuided.captiveDns(env().copy(portal = true), direct).cause)
        val inconclusive = DiagnosticsGuided.captiveDns(env().copy(validated = false), direct)
        assertEquals(DiagnosticConfidence.UNKNOWN, inconclusive.confidence)
        assertEquals(DiagnosticCause.INTERNET_UNCONFIRMED, inconclusive.cause)
    }
    @Test fun httpRejectionDoesNotBecomeVpnOrDpiDiagnosis() {
        val direct = DiagnosticResult.of(DiagnosticRequest(target, DiagnosticPath.DIRECT), DiagnosticState.AVAILABLE)
        val current = DiagnosticResult.of(DiagnosticRequest(target, DiagnosticPath.CURRENT_MCRF), DiagnosticState.ERROR, DiagnosticIssue.HTTP_REJECTED)
        val result = DiagnosticsGuided.service(direct, current)
        assertEquals(DiagnosticCause.HTTP_POLICY, result.cause); assertEquals(listOf(direct, current), result.measurements)
    }
    @Test fun isolatedProviderFailureDoesNotSkipServiceLayer() {
        val pipeline = DiagnosticsGuided({ env().copy(serverSelected = true) }, ServiceProbe { request, _ -> DiagnosticResult.of(request, DiagnosticState.AVAILABLE) },
            { _, _ -> throw IllegalStateException("https://secret@example.org") })
        val result = pipeline.run(target, DiagnosticCancellation(1000))
        assertEquals(DiagnosticStep.SERVICE, result.findings.last().step)
        assertFalse(result.toString().contains("secret"))
    }
    @Test fun validatedNetworkDoesNotInventUnmeasuredTargetDns() {
        val result = DiagnosticResult.of(DiagnosticRequest(target, DiagnosticPath.DIRECT), DiagnosticState.UNAVAILABLE, DiagnosticIssue.UNSUPPORTED_PROTOCOL)
        val finding = DiagnosticsGuided.captiveDns(env(), result)
        assertEquals(DiagnosticState.UNAVAILABLE, finding.state); assertEquals(DiagnosticConfidence.UNKNOWN, finding.confidence)
    }
}
