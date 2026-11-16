package org.mcrf.client

import java.net.InetAddress
import java.net.Socket

/** Only an owned, authenticated loopback SOCKS listener; hostnames stay remote. */
internal object DiagnosticsSocksConnect {
    fun connect(socket: Socket, host: String, user: String, password: String, token: DiagnosticCancellation) {
        fun read(): Int {
            token.check(); socket.soTimeout = token.remainingMs()
            return socket.inputStream.read().also { if (it < 0) throw DiagnosticProxyException(DiagnosticProxyFailure.PROTOCOL) }
        }
        fun expect(value: Int) { if (read() != value) throw DiagnosticProxyException(DiagnosticProxyFailure.PROTOCOL) }
        fun send(bytes: ByteArray) {
            try { token.check(); socket.outputStream.write(bytes); socket.outputStream.flush() } finally { bytes.fill(0) }
        }
        send(byteArrayOf(5, 1, 2)); expect(5)
        if (read() != 2) throw DiagnosticProxyException(DiagnosticProxyFailure.AUTHENTICATION)
        val username = user.toByteArray(Charsets.UTF_8)
        val secret = password.toByteArray(Charsets.UTF_8)
        try {
            require(username.size in 1..255 && secret.size in 1..255)
            send(byteArrayOf(1, username.size.toByte()) + username + byteArrayOf(secret.size.toByte()) + secret)
        } finally { username.fill(0); secret.fill(0) }
        expect(1)
        if (read() != 0) throw DiagnosticProxyException(DiagnosticProxyFailure.AUTHENTICATION)
        val address = if (host.startsWith("[") && host.endsWith("]")) {
            val numeric = host.substring(1, host.length - 1)
            require(numeric.contains(':') && numeric.matches(Regex("[0-9a-fA-F:]+")))
            byteArrayOf(4) + InetAddress.getByName(numeric).address.also { require(it.size == 16) }
        } else {
            val domain = host.toByteArray(Charsets.US_ASCII)
            require(domain.size in 1..253 && host.matches(Regex("[a-zA-Z0-9.-]+")))
            byteArrayOf(3, domain.size.toByte()) + domain
        }
        send(byteArrayOf(5, 1, 0) + address + byteArrayOf(1, 0xbb.toByte()))
        expect(5)
        if (read() != 0) throw DiagnosticProxyException(DiagnosticProxyFailure.CONNECT_REJECTED)
        expect(0)
        val length = when (read()) { 1 -> 4; 4 -> 16; 3 -> read().also { if (it == 0) throw DiagnosticProxyException(DiagnosticProxyFailure.PROTOCOL) }; else -> throw DiagnosticProxyException(DiagnosticProxyFailure.PROTOCOL) }
        repeat(length + 2) { read() }
        token.check()
    }
}
