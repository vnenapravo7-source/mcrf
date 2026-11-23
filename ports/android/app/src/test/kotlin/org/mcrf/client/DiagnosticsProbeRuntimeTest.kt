package org.mcrf.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.Base64
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test

/** Scripted loopback transport and TLS boundary. Exercises actual probe/HTTP/DNS
 * code, without Internet, platform DNS or a claim of real TLS/native acceptance. */
class DiagnosticsProbeRuntimeTest {
    @Test fun exitCountryFallbackKeepsTheSelectedProxyAndTlsIdentity() {
        val sockets=mutableListOf<WireSocket>();var attempts=0
        val trace="ip=203.0.113.7\nloc=DE\n"
        val response=("HTTP/1.1 200 OK\r\nContent-Length: ${trace.toByteArray().size}\r\n\r\n"+trace).toByteArray()
        val result=probe(sockets,{WireSocket({response})},handshake={if(attempts++==0)throw javax.net.ssl.SSLHandshakeException("first endpoint closed")})
            .exitTrace(DiagnosticCancellation(5000))
        assertEquals("DE",AutoExclusions.country(result));assertEquals(2,sockets.size)
        assertTrue(sockets.all {it.closed&&(it.endpoint as InetSocketAddress).port==12345})
        assertTrue(sockets[0].written.toString().contains("CONNECT www.cloudflare.com:443"))
        assertTrue(sockets[1].written.toString().contains("CONNECT speed.cloudflare.com:443"))
        assertTrue(sockets[1].written.toString().contains("Host: speed.cloudflare.com\r\n"))
    }
    @Test fun cancelledExitTraceNeverRetriesAnotherEndpoint() {
        val token=DiagnosticCancellation(5000);val sockets=mutableListOf<WireSocket>()
        val transport=probe(sockets,{WireSocket({error("No HTTP after cancelled TLS")})},handshake={token.cancel();throw javax.net.ssl.SSLException("cancelled")})
        assertThrows(java.util.concurrent.CancellationException::class.java){transport.exitTrace(token)}
        assertEquals(1,sockets.size);assertTrue(sockets.single().closed)
    }
    private class WireSocket(val origin: (String) -> ByteArray,
        proxyReply: String = "HTTP/1.1 200 Connection established\r\n\r\n",
        val connected: () -> Unit = {}) : Socket() {
        private var input = ByteArrayInputStream(proxyReply.toByteArray())
        val written = ByteArrayOutputStream()
        var endpoint: SocketAddress? = null
        var closed = false
        override fun connect(endpoint: SocketAddress, timeout: Int) { this.endpoint = endpoint; connected() }
        override fun getInputStream() = input
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { written.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                written.write(bytes, offset, length)
                val text = bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
                if (text.startsWith("HEAD ") || text.startsWith("GET ")) input = ByteArrayInputStream(origin(text))
            }
        }
        override fun setSoTimeout(timeout: Int) = Unit
        override fun close() { closed = true }
    }
    private class TlsBoundary(private val raw: Socket, private val handshake: (SSLParameters) -> Unit) : SSLSocket() {
        private var parameters = SSLParameters()
        override fun getInputStream() = raw.getInputStream()
        override fun getOutputStream() = raw.getOutputStream()
        override fun close() = raw.close()
        override fun setSoTimeout(timeout: Int) = raw.setSoTimeout(timeout)
        override fun getSSLParameters() = parameters
        override fun setSSLParameters(value: SSLParameters) { parameters = value }
        override fun startHandshake() = handshake(parameters)
        override fun getSupportedCipherSuites() = emptyArray<String>()
        override fun getEnabledCipherSuites() = emptyArray<String>()
        override fun setEnabledCipherSuites(value: Array<out String>) = Unit
        override fun getSupportedProtocols() = emptyArray<String>()
        override fun getEnabledProtocols() = emptyArray<String>()
        override fun setEnabledProtocols(value: Array<out String>) = Unit
        override fun getSession(): SSLSession = throw UnsupportedOperationException()
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) = Unit
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) = Unit
        override fun setUseClientMode(value: Boolean) = Unit
        override fun getUseClientMode() = true
        override fun setNeedClientAuth(value: Boolean) = Unit
        override fun getNeedClientAuth() = false
        override fun setWantClientAuth(value: Boolean) = Unit
        override fun getWantClientAuth() = false
        override fun setEnableSessionCreation(value: Boolean) = Unit
        override fun getEnableSessionCreation() = true
    }
    private fun request(id: String = "github-site") = DiagnosticRequest(requireNotNull(ServiceTestCatalog.find(id)), DiagnosticPath.CURRENT_MCRF)
    private fun head(status: Int) = "HTTP/1.1 $status Result\r\nContent-Length: 0\r\n\r\n".toByteArray()
    private fun dnsReply(text: String, rcode: Int = 0): ByteArray {
        val encoded = text.substringAfter("?dns=").substringBefore(' ')
        val query = Base64.getUrlDecoder().decode(encoded)
        val question = PacketDnsWire.question(query)
        assertEquals("discord.media", question.name)
        val address = InetAddress.getByAddress(if (question.type == 1) byteArrayOf(1, 2, 3, 4) else ByteArray(16).apply { this[0] = 32; this[15] = 1 })
        val reply = PacketDnsWire.reply(query, rcode, if (rcode == 0) listOf(address) else emptyList())
        return ("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: ${reply.size}\r\n\r\n").toByteArray() + reply
    }
    private fun probe(sockets: MutableList<WireSocket>, factory: () -> WireSocket,
        live: () -> Boolean = { true }, running: () -> Boolean = { true }, handshake: () -> Unit = {}) =
        RealServiceProbe(null, { error("must never use physical DNS") }, 12345, "private-auth", live,
            proxyRunning = running, proxySocket = { factory().also { sockets.add(it) } }, tlsSocket = { raw, host ->
                TlsBoundary(raw) { params ->
                    assertEquals("HTTPS", params.endpointIdentificationAlgorithm)
                    assertEquals(host, (params.serverNames.single() as SNIHostName).asciiName)
                    handshake()
                }
            })

    @Test fun dnsUsesAuthenticatedSelectedProxyForActualBoundAAndAaaaReplies() {
        val sockets = mutableListOf<WireSocket>()
        val result = probe(sockets, { WireSocket({ dnsReply(it) }) }).execute(request("discord-voice"), DiagnosticCancellation(5000))
        assertEquals(DiagnosticState.AVAILABLE, result.state)
        assertEquals(DiagnosticDnsScope.CONTROL_DOH_THROUGH_PROXY, result.dnsScope)
        assertEquals(2, sockets.size)
        assertTrue(sockets.all { it.closed && (it.endpoint as InetSocketAddress).address.isLoopbackAddress })
        assertTrue(sockets.all { it.written.toString().startsWith("CONNECT dns.google:443") })
        assertTrue(sockets.all { !it.written.toString().substringAfter("GET ").contains("Proxy-Authorization") })
        val dns = result.evidence.last { it.stage == DiagnosticStage.DNS }
        assertEquals(1, dns.ipv4Answers); assertEquals(1, dns.ipv6Answers)
        val row = DiagnosticsPresentation.comparisonEntries(listOf(serverServiceMeasurement(1, result)), setOf(1), setOf(result.targetId)).single()
        assertTrue(row.reason.contains("Только DNS")); assertTrue(row.reason.contains("работа приложения не проверен"))
        assertTrue(row.reason.contains("DNS профиля")); assertFalse(row.reason.contains("private-auth"))
    }

    @Test fun targetNxdomainAndControlHttpRejectionHaveDifferentReasons() {
        val nxdomain = probe(mutableListOf(), { WireSocket({ dnsReply(it, 3) }) })
            .execute(request("discord-voice"), DiagnosticCancellation(5000))
        assertEquals(DiagnosticDnsFailure.TARGET_NO_ADDRESS, nxdomain.dnsFailure)
        assertEquals(DiagnosticIssue.NO_ADDRESS, nxdomain.issue)
        assertEquals(3, nxdomain.evidence.last().dnsRcode)
        assertTrue(DiagnosticsPresentation.resultReason(nxdomain).contains("NXDOMAIN"))
        val control = probe(mutableListOf(), { WireSocket({ head(403) }) })
            .execute(request("discord-voice"), DiagnosticCancellation(5000))
        assertEquals(DiagnosticDnsFailure.CONTROL_RESPONSE, control.dnsFailure)
        assertEquals(403, control.evidence.last().httpStatus)
        assertTrue(DiagnosticsPresentation.resultReason(control).contains("DoH-резолвер"))
        assertFalse(DiagnosticsPresentation.resultReason(control).contains("NXDOMAIN"))
    }

    @Test fun controlTlsFailureDoesNotClaimTheTargetNameFailedDns() {
        val sockets = mutableListOf<WireSocket>()
        val result = probe(sockets, { WireSocket({ error("no HTTP before TLS") }) },
            handshake = { throw javax.net.ssl.SSLHandshakeException("private-details") })
            .execute(request("discord-voice"), DiagnosticCancellation(5000))
        assertEquals(DiagnosticIssue.TLS_FAILED, result.issue)
        assertEquals(DiagnosticDnsFailure.CONTROL_TRANSPORT, result.dnsFailure)
        assertTrue(DiagnosticsPresentation.resultReason(result).contains("DNS целевого имени не проверен"))
        assertTrue(sockets.all { it.closed }); assertFalse(result.toString().contains("private-details"))
    }

    @Test fun rejectedHeadRetriesExactlyOneGetAndPreservesBothStatusCodes() {
        val sockets = mutableListOf<WireSocket>()
        val result = probe(sockets, { WireSocket({ head(if (it.startsWith("HEAD")) 405 else 200) }) })
            .execute(request(), DiagnosticCancellation(5000))
        assertEquals(DiagnosticState.AVAILABLE, result.state)
        assertEquals(listOf(405, 200), result.evidence.mapNotNull { it.httpStatus })
        assertEquals(2, sockets.size); assertTrue(sockets.all { it.closed })
        assertTrue(sockets[0].written.toString().contains("HEAD /")); assertTrue(sockets[1].written.toString().contains("GET /"))
    }

    @Test fun httpPolicyAndServerErrorsStayFailuresWithTheirActualCodes() {
        for (status in listOf(401, 403, 429, 500, 503)) {
            val sockets = mutableListOf<WireSocket>()
            val result = probe(sockets, { WireSocket({ head(status) }) }).execute(request(), DiagnosticCancellation(5000))
            val measurement = serverServiceMeasurement(1, result)
            assertEquals(DiagnosticState.ERROR, measurement.state)
            assertEquals(DiagnosticIssue.HTTP_REJECTED, measurement.diagnostic?.issue)
            assertEquals(1, sockets.size)
            assertTrue(DiagnosticsPresentation.resultReason(result).contains("HTTP $status"))
            assertEquals(ServiceTestResults.Group.FAILURE, ServiceTestResults.group(result))
        }
    }

    @Test fun redirectIsAnOriginResponseAndNeverAFollowedDestination() {
        val sockets = mutableListOf<WireSocket>()
        val result = probe(sockets, { WireSocket({ "HTTP/1.1 302 Found\r\nLocation: https://other.invalid/\r\n\r\n".toByteArray() }) })
            .execute(request(), DiagnosticCancellation(5000))
        assertEquals(DiagnosticState.AVAILABLE, result.state); assertEquals(1, sockets.size)
        assertTrue(DiagnosticsPresentation.resultReason(result).contains("перенаправление не проверено"))
        assertFalse(result.toString().contains("other.invalid"))
    }

    @Test fun proxyAuthRejectionNeverBecomesAnOriginHttpFailure() {
        val sockets = mutableListOf<WireSocket>()
        val result = probe(sockets, { WireSocket({ error("must not reach origin") }, "HTTP/1.1 407 Authentication required\r\n\r\n") },
            handshake = { error("must not start TLS") }).execute(request(), DiagnosticCancellation(5000))
        val measurement = serverServiceMeasurement(1, result)
        assertEquals(ServerComparisonLimit.PROXY_AUTH, measurement.limitation)
        assertEquals(ServiceTestResults.Group.UNKNOWN, ServiceTestResults.group(result))
        assertTrue(sockets.all { it.closed }); assertFalse(result.toString().contains("private-auth"))
    }

    @Test fun cancellationBetweenAllocationAndAttachmentClosesSocketAndSendsNothing() {
        val token = DiagnosticCancellation(5000); val sockets = mutableListOf<WireSocket>()
        val result = probe(sockets, { token.cancel(); WireSocket({ error("no request") }) }).execute(request(), token)
        assertEquals(DiagnosticState.CANCELLED, result.state)
        assertTrue(sockets.single().closed); assertEquals(0, sockets.single().written.size())
    }

    @Test fun cancellationDuringConnectWinsOverSocketClosureAndDoesNotStartTls() {
        val token = DiagnosticCancellation(5000); val sockets = mutableListOf<WireSocket>()
        val result = probe(sockets, { WireSocket({ error("no request") }, connected = { token.cancel(); throw java.io.IOException() }) })
            .execute(request(), token)
        assertEquals(DiagnosticState.CANCELLED, result.state); assertTrue(sockets.single().closed)
    }

    @Test fun deadChildAndChangedNetworkAreDifferentUnavailableEvidence() {
        val stopped = probe(mutableListOf(), { error("must not connect") }, running = { false })
            .execute(request(), DiagnosticCancellation(5000))
        assertEquals(ServerComparisonLimit.PROXY_EXITED, serverServiceMeasurement(1, stopped).limitation)
        val changed = probe(mutableListOf(), { error("must not connect") }, live = { false })
            .execute(request(), DiagnosticCancellation(5000))
        assertEquals(DiagnosticIssue.PATH_CHANGED, changed.issue); assertNull(changed.proxyFailure)
    }

    @Test fun childExitDuringTlsOrHttpIsUnknownRatherThanAnOriginFailure() {
        for (duringTls in listOf(true, false)) {
            var running = true
            val sockets = mutableListOf<WireSocket>()
            val result = probe(sockets, { WireSocket({
                running = false
                throw java.io.IOException("child closed during HTTP")
            }) }, running = { running }, handshake = {
                if (duringTls) {
                    running = false
                    throw javax.net.ssl.SSLException("child closed during TLS")
                }
            }).execute(request(), DiagnosticCancellation(5000))
            assertEquals(DiagnosticProxyFailure.STOPPED, result.proxyFailure)
            assertEquals(ServerComparisonLimit.PROXY_EXITED, serverServiceMeasurement(1, result).limitation)
            assertEquals(ServiceTestResults.Group.UNKNOWN, ServiceTestResults.group(result))
            assertTrue(sockets.single().closed)
        }
    }

    @Test fun cancellationWinsOverConcurrentChildExitDuringTls() {
        val token = DiagnosticCancellation(5000)
        var running = true
        val sockets = mutableListOf<WireSocket>()
        val result = probe(sockets, { WireSocket({ error("no HTTP after cancellation") }) },
            running = { running }, handshake = {
                running = false; token.cancel()
                throw javax.net.ssl.SSLException("cancelled child")
            }).execute(request(), token)
        assertEquals(DiagnosticState.CANCELLED, result.state)
        assertEquals(DiagnosticIssue.CANCELLED, result.issue)
        assertNull(result.proxyFailure)
        assertTrue(sockets.single().closed)
    }
}
