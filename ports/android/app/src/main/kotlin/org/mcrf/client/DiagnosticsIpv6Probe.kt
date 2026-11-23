package org.mcrf.client

import java.net.Inet6Address
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** Direct = platform AAAA + forced IPv6 TLS. Current = explicit control DoH over
 * the existing proxy + forced IPv6 CONNECT. The latter is NOT the profile DNS
 * resolver and does NOT establish which VPN/server a routing rule selected. */
class DiagnosticsIpv6Probe(private val probe: RealServiceProbe, private val environment: DiagnosticsEnvironment) {
    fun run(target: ServiceTestTarget, token: DiagnosticCancellation): Ipv6DiagnosticsReport {
        require(ServiceTestCatalog.find(target.id) == target)
        val paths = DiagnosticPath.values().map { path ->
            token.check()
            if (!probe.available(path)) Ipv6PathReport(path, DiagnosticState.UNAVAILABLE, null, null,
                Ipv6Capability.NOT_MEASURED, Ipv6Capability.NOT_MEASURED, path == DiagnosticPath.CURRENT_MCRF,
                if (path == DiagnosticPath.DIRECT) DiagnosticIssue.NO_DIRECT_NETWORK else DiagnosticIssue.NO_MCRF_PROXY)
            else if (target.check != DiagnosticCheck.HTTPS) Ipv6PathReport(path, DiagnosticState.UNAVAILABLE, null, null,
                Ipv6Capability.NOT_MEASURED, Ipv6Capability.NOT_MEASURED, false, DiagnosticIssue.UNSUPPORTED_PROTOCOL)
            else withDiagnosticBudget(token, 8000) { child -> if (path == DiagnosticPath.DIRECT) direct(target, child) else current(target, child) }
        }
        return Ipv6DiagnosticsReport(target.id, paths, environment)
    }
    private fun direct(target: ServiceTestTarget, token: DiagnosticCancellation): Ipv6PathReport {
        val raw = DiagnosticsPipeline(probe).execute(DiagnosticRequest(target, DiagnosticPath.DIRECT, DiagnosticFamily.IPV6), token)
        val result = if (raw.issue == DiagnosticIssue.NO_ADDRESS) raw.copy(state = DiagnosticState.UNAVAILABLE) else raw
        val dns = result.evidence.lastOrNull { it.stage == DiagnosticStage.DNS }
        return Ipv6PathReport(DiagnosticPath.DIRECT, dns?.state ?: DiagnosticState.UNAVAILABLE, dns?.ipv6Answers,
            result, if (Ipv6Diagnostics.verdict(result) == Ipv6Diagnostics.Verdict.REACHABLE) Ipv6Capability.OBSERVED else if (environment.physicalIpv6Route == true) Ipv6Capability.ADVERTISED else Ipv6Capability.NOT_MEASURED,
            Ipv6Capability.NOT_MEASURED, false, result.issue)
    }
    private fun current(target: ServiceTestTarget, token: DiagnosticCancellation): Ipv6PathReport {
        val path = DiagnosticPath.CURRENT_MCRF
        var answers: List<Inet6Address>? = null
        val started = System.nanoTime()
        val evidence = mutableListOf<DiagnosticEvidence>()
        var issue = DiagnosticIssue.DNS_FAILED
        try {
            val query = PacketDnsWire.query(target.host, 28, java.security.SecureRandom().nextInt(65536))
            // https://developers.google.com/speed/public-dns/docs/doh (RFC 8484).
            // Fixed endpoint; no arbitrary URL or response logging.
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(query)
            val reply = probe.ipv6Https("dns.google", null, path, token) { socket ->
                val request = "GET /dns-query?dns=$encoded HTTP/1.1\r\nHost: dns.google\r\nAccept: application/dns-message\r\nConnection: close\r\n\r\n"
                socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                DiagnosticsHttp.dnsBody(socket, token)
            }
            PacketDnsWire.response(query, reply)
            if ((PacketDnsWire.u16(reply, 2) and 15) !in setOf(0, 3)) throw java.io.IOException()
            answers = PacketDnsWire.addresses(query, reply).filterIsInstance<Inet6Address>().distinct().take(8)
            evidence.add(DiagnosticEvidence(DiagnosticStage.DNS, DiagnosticState.AVAILABLE, ipv6Answers = answers.size))
            if (answers.isEmpty()) return Ipv6PathReport(path, DiagnosticState.AVAILABLE, 0, null,
                Ipv6Capability.NOT_MEASURED, Ipv6Capability.NOT_MEASURED, true, DiagnosticIssue.NO_ADDRESS)
            issue = DiagnosticIssue.CONNECT_FAILED
            val status = probe.ipv6Https(target.host, answers.first(), path, token) { socket ->
                evidence.add(DiagnosticEvidence(DiagnosticStage.CONNECT, DiagnosticState.AVAILABLE))
                evidence.add(DiagnosticEvidence(DiagnosticStage.TLS, DiagnosticState.AVAILABLE))
                issue = DiagnosticIssue.HTTP_REJECTED
                socket.outputStream.write("HEAD / HTTP/1.1\r\nHost: ${target.host}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                DiagnosticsHttp.status(socket, token)
            }
            val state = if (status in 200..399) DiagnosticState.AVAILABLE else DiagnosticState.ERROR
            issue = if (state == DiagnosticState.AVAILABLE) DiagnosticIssue.NONE else DiagnosticIssue.HTTP_REJECTED
            evidence.add(DiagnosticEvidence(DiagnosticStage.HTTP, state, issue, httpStatus = status))
            val result = DiagnosticResult(target.id, path, DiagnosticFamily.IPV6, state, issue,
                (System.nanoTime() - started) / 1_000_000, evidence.toList())
            return Ipv6PathReport(path, DiagnosticState.AVAILABLE, answers.size, result,
                Ipv6Capability.OBSERVED,
                Ipv6Capability.NOT_MEASURED, true, issue)
        } catch (error: Exception) {
            token.checkCancellation()
            if (error is CancellationException) throw error
            issue = if (!probe.live(path)) DiagnosticIssue.PATH_CHANGED else when (error) {
                is SocketTimeoutException -> DiagnosticIssue.TIMEOUT
                is SSLException -> DiagnosticIssue.TLS_FAILED
                else -> issue
            }
            val state = if (issue == DiagnosticIssue.PATH_CHANGED) DiagnosticState.UNAVAILABLE else DiagnosticState.ERROR
            val result = if (answers == null) null else DiagnosticResult(target.id, path, DiagnosticFamily.IPV6, state, issue,
                (System.nanoTime() - started) / 1_000_000, evidence.toList())
            return Ipv6PathReport(path, if (answers == null) state else DiagnosticState.AVAILABLE, answers?.size, result,
                Ipv6Capability.NOT_MEASURED, Ipv6Capability.NOT_MEASURED, true, issue)
        }
    }
}
