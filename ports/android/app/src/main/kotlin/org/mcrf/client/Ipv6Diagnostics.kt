package org.mcrf.client

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** AAAA answers are not proof of an IPv6 route, TLS reachability or VPN support. */
object Ipv6Diagnostics {
    enum class Verdict { NOT_MEASURED, REACHABLE, FAILED, INCONCLUSIVE }
    data class Answers(val ipv4: Int, val ipv6: Int)
    fun answers(addresses: List<InetAddress>) = Answers(
        addresses.count { it is Inet4Address }, addresses.count { it is Inet6Address }
    )
    fun select(addresses: List<InetAddress>, family: DiagnosticFamily): List<InetAddress> = addresses.filter {
        when (family) {
            DiagnosticFamily.AUTOMATIC -> true
            DiagnosticFamily.IPV4 -> it is Inet4Address
            DiagnosticFamily.IPV6 -> it is Inet6Address
        }
    }
    fun verdict(result: DiagnosticResult?): Verdict = when {
        result == null || result.family != DiagnosticFamily.IPV6 -> Verdict.NOT_MEASURED
        result.issue in setOf(DiagnosticIssue.NO_ADDRESS, DiagnosticIssue.DNS_FAILED, DiagnosticIssue.PATH_CHANGED) -> Verdict.INCONCLUSIVE
        result.state in setOf(DiagnosticState.AVAILABLE, DiagnosticState.ERROR) && result.evidence.any {
            it.stage == DiagnosticStage.HTTP && (it.state == DiagnosticState.AVAILABLE || it.httpStatus != null)
        } -> Verdict.REACHABLE
        result.state == DiagnosticState.ERROR -> Verdict.FAILED
        else -> Verdict.INCONCLUSIVE
    }
}
