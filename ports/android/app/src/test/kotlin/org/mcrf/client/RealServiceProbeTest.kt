package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Offline guards: none of these cases opens a network socket or resolver. */
class RealServiceProbeTest {
    private fun probe(port: Int? = null, live: Boolean = true) = RealServiceProbe(null, { false }, port, "secret", { live })
    private fun request(id: String, path: DiagnosticPath = DiagnosticPath.CURRENT_MCRF, family: DiagnosticFamily = DiagnosticFamily.AUTOMATIC) =
        DiagnosticRequest(ServiceTestCatalog.find(id)!!, path, family)
    @Test fun unavailablePathNeverFallsBackToDefaultNetwork() {
        val token = DiagnosticCancellation(1000)
        assertEquals(DiagnosticIssue.NO_DIRECT_NETWORK, probe().execute(request("github-site", DiagnosticPath.DIRECT), token).issue)
        assertEquals(DiagnosticIssue.NO_MCRF_PROXY, probe().execute(request("github-site"), token).issue)
    }
    @Test fun forcedFamilyWithoutAnObservedProxyAnswerIsExplicitlyUnmeasured() {
        assertEquals(DiagnosticIssue.UNSUPPORTED_PROTOCOL, probe(12345).execute(request("github-site", family = DiagnosticFamily.IPV6), DiagnosticCancellation(1000)).issue)
    }
    @Test fun changedSessionAndAddressOnlyPartsCannotBeSuccess() {
        assertEquals(DiagnosticIssue.PATH_CHANGED, probe(12345, false).execute(request("github-site"), DiagnosticCancellation(1000)).issue)
        val result = probe(12345).execute(request("riot-udp"), DiagnosticCancellation(1000))
        assertEquals(DiagnosticState.UNAVAILABLE, result.state)
        assertFalse(result.toString().contains("secret")); assertFalse(probe().toString().contains("secret"))
    }
    @Test fun callerCannotSmuggleAHostBehindKnownCatalogKey() {
        val forged = request("github-site").let { it.copy(target = it.target.copy(host = "example.org")) }
        try { probe(12345).execute(forged, DiagnosticCancellation(1000)); fail() } catch (_: IllegalArgumentException) { }
    }
}
