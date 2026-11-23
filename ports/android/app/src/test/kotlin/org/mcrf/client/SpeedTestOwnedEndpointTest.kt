package org.mcrf.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import org.junit.Assert.*
import org.junit.Test

/** Fake sockets only. These tests do not open a native child, network socket or TLS session. */
class SpeedTestOwnedEndpointTest {
    private class Wire(reply: ByteArray) : Socket() {
        val sent = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(reply)
        var destination: InetSocketAddress? = null
        var retired = false
        override fun getInputStream() = input
        override fun getOutputStream() = sent
        override fun connect(endpoint: SocketAddress?, timeout: Int) { destination = endpoint as InetSocketAddress }
        override fun setSoTimeout(timeout: Int) = Unit
        override fun close() { retired = true }
    }
    @Test fun mixedIngressUsesAuthenticatedConnectOnlyToOwnedLoopback() {
        val wire = Wire("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
        val endpoint = DiagnosticsOwnedEndpoint(19001, "fixture", "secret", false, { wire }) {}
        endpoint.connect(SpeedTestProviders.adapter("cloudflare"), DiagnosticCancellation(1000)).use {
            assertSame(wire, it.socket)
            assertTrue(wire.destination!!.address.isLoopbackAddress)
            assertEquals(19001, wire.destination!!.port)
            val sent = wire.sent.toString("US-ASCII")
            assertTrue(sent.startsWith("CONNECT speed.cloudflare.com:443 HTTP/1.1\r\n"))
            assertTrue(sent.contains("Proxy-Authorization: Basic Zml4dHVyZTpzZWNyZXQ="))
            assertFalse(endpoint.toString().contains("secret"))
        }
        assertTrue(wire.retired)
    }
    @Test fun carrierUsesSocksAuthAndRemoteHostAndRejectsUnauthenticatedReply() {
        val wire = Wire(byteArrayOf(5, 2, 1, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 1))
        DiagnosticsOwnedEndpoint(19002, "fixture", "secret", true, { wire }) {}
            .connect(SpeedTestProviders.adapter("cloudflare"), DiagnosticCancellation(1000)).close()
        val sent = wire.sent.toByteArray()
        assertArrayEquals(byteArrayOf(5, 1, 2), sent.copyOfRange(0, 3))
        assertTrue(sent.toString(Charsets.ISO_8859_1).contains("speed.cloudflare.com"))
        val rejected = Wire(byteArrayOf(5, 0))
        assertThrows(DiagnosticProxyException::class.java) {
            DiagnosticsOwnedEndpoint(19002, "fixture", "secret", true, { rejected }) {}
                .connect(SpeedTestProviders.adapter("cloudflare"), DiagnosticCancellation(1000))
        }
        assertTrue(rejected.retired)
    }
    @Test fun cancelledOrDeadOwnerCannotConnectAndCancellationClosesBorrowedTunnel() {
        val wire = Wire("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
        var created = 0
        val endpoint = DiagnosticsOwnedEndpoint(19001, "fixture", "secret", false, { created++; wire }) {}
        val cancelled = DiagnosticCancellation(1000).apply { cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) { endpoint.connect(SpeedTestProviders.adapter("cloudflare"), cancelled) }
        assertEquals(0, created)
        val token = DiagnosticCancellation(1000)
        endpoint.connect(SpeedTestProviders.adapter("cloudflare"), token).use {
            token.cancel(); assertTrue(wire.retired)
        }
        val dead = DiagnosticsOwnedEndpoint(19001, "fixture", "secret", false, { error("must not allocate") }) { throw SpeedTestRouteChanged() }
        assertThrows(SpeedTestRouteChanged::class.java) { dead.connect(SpeedTestProviders.adapter("cloudflare"), DiagnosticCancellation(1000)) }
    }
}
