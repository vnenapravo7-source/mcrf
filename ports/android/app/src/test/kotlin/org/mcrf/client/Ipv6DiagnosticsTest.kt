package org.mcrf.client

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class Ipv6DiagnosticsTest {
    @Test fun answersAndFamilySelectionDoNotPerformNameResolution() {
        val v4 = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        val v6 = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
        val addresses = listOf(v4, v6)
        assertEquals(Ipv6Diagnostics.Answers(1, 1), Ipv6Diagnostics.answers(addresses))
        assertEquals(listOf(v6), Ipv6Diagnostics.select(addresses, DiagnosticFamily.IPV6))
        assertEquals(listOf(v4), Ipv6Diagnostics.select(addresses, DiagnosticFamily.IPV4))
        assertEquals(addresses, Ipv6Diagnostics.select(addresses, DiagnosticFamily.AUTOMATIC))
    }
    @Test fun aaaaAndAutomaticSuccessCannotCertifyIpv6Https() {
        val request = DiagnosticRequest(ServiceTestCatalog.find("github-site")!!, DiagnosticPath.DIRECT, DiagnosticFamily.IPV6)
        val dnsOnly = DiagnosticResult.of(request, DiagnosticState.AVAILABLE).copy(evidence = listOf(
            DiagnosticEvidence(DiagnosticStage.DNS, DiagnosticState.AVAILABLE, ipv6Answers = 2)))
        assertEquals(Ipv6Diagnostics.Verdict.INCONCLUSIVE, Ipv6Diagnostics.verdict(dnsOnly))
        val https = dnsOnly.copy(evidence = dnsOnly.evidence + DiagnosticEvidence(DiagnosticStage.HTTP, DiagnosticState.AVAILABLE, httpStatus = 200))
        assertEquals(Ipv6Diagnostics.Verdict.REACHABLE, Ipv6Diagnostics.verdict(https))
        assertEquals(Ipv6Diagnostics.Verdict.NOT_MEASURED, Ipv6Diagnostics.verdict(https.copy(family = DiagnosticFamily.AUTOMATIC)))
    }
}
