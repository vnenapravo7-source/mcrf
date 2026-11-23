package org.mcrf.client

import java.io.ByteArrayInputStream
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsHttpTest {
    private class MemorySocket(bytes: ByteArray) : Socket() {
        private val input = ByteArrayInputStream(bytes)
        override fun getInputStream() = input
        override fun setSoTimeout(timeout: Int) = Unit
    }
    private fun response(headers: String, body: ByteArray) = MemorySocket(("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\n$headers\r\n").toByteArray() + body)
    private fun head(text: String) = DiagnosticsHttp.head(MemorySocket(text.toByteArray(Charsets.US_ASCII)), DiagnosticCancellation(5000))
    private fun header(name: String, wireBytes: Int): String {
        val prefix = "$name: "
        return prefix + "a".repeat(wireBytes - prefix.length - 2) + "\r\n"
    }
    @Test fun acceptsRealisticLongCspWithoutChangingRemoteStatus() {
        val prefix = "Content-Security-Policy: default-src 'none'; script-src 'self' "
        val csp = prefix + "https://assets.example.test ".repeat(160).take(4214 - prefix.length - 1) + ";"
        assertEquals(4214, csp.length)
        for (status in listOf(200, 403, 503)) {
            val parsed = head("HTTP/1.1 $status Result\r\n$csp\r\nContent-Length: 0\r\n\r\n")
            assertEquals(status, parsed.status)
            assertEquals(csp.substringAfter(": "), parsed.headers["content-security-policy"])
        }
    }
    @Test fun headerLineLimitIncludesCrlfAndAcceptsExactly16KiB() {
        val status = "HTTP/1.1 200 OK\r\n"
        assertEquals(200, head(status + header("X-Large", 16 * 1024) + "\r\n").status)
        assertThrows(DiagnosticsHttp.MalformedResponse::class.java) {
            head(status + header("X-Large", 16 * 1024 + 1) + "\r\n")
        }
    }
    @Test fun aggregateLimitIncludesStatusLinesAndFinalCrlf() {
        val status = "HTTP/1.1 200 OK\r\n"
        val first = (1..3).joinToString("") { header("X-$it", 16 * 1024) }
        val lastBytes = 64 * 1024 - status.length - first.length - 2
        assertEquals(200, head(status + first + header("X-Last", lastBytes) + "\r\n").status)
        assertThrows(DiagnosticsHttp.MalformedResponse::class.java) {
            head(status + first + header("X-Last", lastBytes + 1) + "\r\n")
        }
        val informational = "HTTP/1.1 103 Early Hints\r\n" + first + "\r\n"
        assertThrows(DiagnosticsHttp.MalformedResponse::class.java) {
            head(informational + status + header("X-Final", 16 * 1024) + "\r\n")
        }
    }
    @Test fun longHeadersStillRequireCrlfAndStatusStaysBounded() {
        val prefix = "HTTP/1.1 200 "
        assertEquals(200, head(prefix + "a".repeat(1024 - prefix.length - 2) + "\r\n\r\n").status)
        for (invalid in listOf(
            prefix + "a".repeat(1024 - prefix.length - 1) + "\r\n\r\n",
            "HTTP/1.1 200 OK\r\nX-Large: " + "a".repeat(4214) + "\n\r\n",
            "HTTP/1.1 200 OK\r\nX-Large: " + "a".repeat(4214) + "\rX\r\n\r\n")) {
            assertThrows(DiagnosticsHttp.MalformedResponse::class.java) { head(invalid) }
        }
    }
    @Test fun readsBoundedContentLengthAndChunkedBodies() {
        val body = byteArrayOf(0, 1, 2, 3)
        assertArrayEquals(body, DiagnosticsHttp.dnsBody(response("Content-Length: 4\r\n", body), DiagnosticCancellation(1000)))
        val chunked = "4\r\n".toByteArray() + body + "\r\n0\r\n\r\n".toByteArray()
        assertArrayEquals(body, DiagnosticsHttp.dnsBody(response("Transfer-Encoding: chunked\r\n", chunked), DiagnosticCancellation(1000)))
    }
    @Test fun rejectsAmbiguousAndUnboundedBodies() {
        for (headers in listOf("Content-Length: 999999\r\n", "Content-Length: 1\r\nContent-Length: 1\r\n", "Transfer-Encoding: chunked\r\nContent-Length: 1\r\n")) {
            try { DiagnosticsHttp.dnsBody(response(headers, byteArrayOf(1)), DiagnosticCancellation(1000)); fail() } catch (_: java.io.IOException) { }
        }
    }
    @Test fun redirectsAreNotFollowedAndDnsWireIsValidatedIndependently() {
        try {
            DiagnosticsHttp.dnsBody(MemorySocket("HTTP/1.1 302 Found\r\nLocation: https://secret@other/\r\n\r\n".toByteArray()), DiagnosticCancellation(1000)); fail()
        } catch (_: java.io.IOException) { }
        val query = PacketDnsWire.query("github.com", 28, 123)
        val answer = PacketDnsWire.reply(query, 0, listOf(java.net.InetAddress.getByAddress(ByteArray(16).apply { this[0] = 32; this[15] = 1 })))
        assertEquals(16, PacketDnsWire.addresses(query, answer).single().address.size)
        answer[0] = 1
        try { PacketDnsWire.response(query, answer); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun boundedInformationalResponsesRequireCompleteFinalHeaders() {
        val response = "HTTP/1.1 103 Early Hints\r\nLink: ignored\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
        assertEquals(200, DiagnosticsHttp.head(MemorySocket(response.toByteArray()), DiagnosticCancellation(1000)).status)
        for (invalid in listOf("HTTP/1.1 200 OK\r\n", "HTTP/1.1 101 Switching Protocols\r\n\r\n",
            "HTTP/1.1 200 OK\r\nmalformed\r\n\r\n", "HTTP/1.1 103 Hint\r\n\r\n".repeat(7))) {
            try { DiagnosticsHttp.head(MemorySocket(invalid.toByteArray()), DiagnosticCancellation(1000)); fail() } catch (_: java.io.IOException) { }
        }
    }
    @Test fun zeroChunkWithoutTerminatingTrailersIsTruncatedNotSuccess() {
        try {
            DiagnosticsHttp.dnsBody(response("Transfer-Encoding: chunked\r\n", "1\r\nx\r\n0\r\n".toByteArray()), DiagnosticCancellation(1000)); fail()
        } catch (_: java.io.IOException) { }
    }
}
