package org.mcrf.client

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Base64

/** Opaque capability minted by an exact temporary-session owner, never serialized. */
internal class DiagnosticsOwnedEndpoint(
    private val port: Int, private val username: String, private val password: String,
    private val socks: Boolean, private val socketFactory: () -> Socket = { Socket(Proxy.NO_PROXY) },
    private val live: () -> Unit
) {
    override fun toString() = "DiagnosticsOwnedEndpoint(redacted)"
    fun check() = live()
    class Tunnel(val socket: Socket, private val lease: AutoCloseable) : AutoCloseable {
        override fun close() { try { socket.close() } finally { lease.close() } }
    }
    fun connect(provider: SpeedTestProviderAdapter, token: DiagnosticCancellation): Tunnel {
        // Only a registry adapter can supply an origin; unavailable providers
        // are rejected before temporary startup, with no caller-supplied URL.
        require(provider === SpeedTestProviders.adapter(provider.id))
        token.check(); check()
        val socket = socketFactory()
        val lease = try { token.attach(socket) } catch (error: Exception) { socket.close(); throw error }
        try {
            socket.soTimeout = token.remainingMs()
            socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), token.remainingMs())
            token.check(); check()
            if (socks) DiagnosticsSocksConnect.connect(socket, provider.host, username, password, token)
            else {
                val credentials = "$username:$password".toByteArray(Charsets.UTF_8)
                val auth = Base64.getEncoder().encode(credentials)
                try {
                    socket.outputStream.write("CONNECT ${provider.host}:443 HTTP/1.1\r\nHost: ${provider.host}:443\r\nProxy-Authorization: Basic ".toByteArray(Charsets.US_ASCII))
                    socket.outputStream.write(auth); socket.outputStream.write("\r\n\r\n".toByteArray(Charsets.US_ASCII)); socket.outputStream.flush()
                } finally { credentials.fill(0); auth.fill(0) }
                if (DiagnosticsHttp.head(socket, token).status != 200) throw SpeedTestRouteUnavailable()
            }
            token.check(); check()
            return Tunnel(socket, lease)
        } catch (error: Throwable) {
            try { socket.close() } finally { lease.close() }
            throw error
        }
    }
}
