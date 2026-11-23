package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsIpv6EvidenceTest {
    @Test fun noAaaaDnsFailureAndChangedPathAreNotBrokenIpv6Verdicts() {
        for (issue in listOf(DiagnosticIssue.NO_ADDRESS, DiagnosticIssue.DNS_FAILED, DiagnosticIssue.PATH_CHANGED)) {
            val result = DiagnosticResult("github-site", DiagnosticPath.DIRECT, DiagnosticFamily.IPV6, DiagnosticState.ERROR, issue)
            assertEquals(Ipv6Diagnostics.Verdict.INCONCLUSIVE, Ipv6Diagnostics.verdict(result))
        }
    }
    @Test fun httpPolicyCanRejectAReachableIpv6Origin() {
        val result = DiagnosticResult("github-site", DiagnosticPath.CURRENT_MCRF, DiagnosticFamily.IPV6, DiagnosticState.ERROR,
            DiagnosticIssue.HTTP_REJECTED, evidence = listOf(DiagnosticEvidence(DiagnosticStage.HTTP, DiagnosticState.ERROR, DiagnosticIssue.HTTP_REJECTED, httpStatus = 403)))
        assertEquals(Ipv6Diagnostics.Verdict.REACHABLE, Ipv6Diagnostics.verdict(result))
    }
    @Test fun savedPolicyDoesNotIgnoreEarlierUnknownConditions() {
        val simple = JSONObject().put("enabled", true).put("text", "DOMAIN,github.com").put("target", "block")
        val state = JSONObject().put("rules", JSONArray().put(simple)).put("defaultTarget", "proxy")
        assertEquals(DiagnosticRoutePolicy.BLOCK, DiagnosticsEnvironmentReader.savedRoute(state, "github.com").policy)
        val prior = JSONObject().put("enabled", true).put("text", "203.0.113.0/24").put("target", "direct")
        state.put("rules", JSONArray().put(prior).put(simple))
        assertEquals(DiagnosticRoutePolicy.UNKNOWN, DiagnosticsEnvironmentReader.savedRoute(state, "github.com").policy)
    }
}
