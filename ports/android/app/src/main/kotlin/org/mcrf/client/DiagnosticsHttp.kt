package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket

/** Strict bounded HTTP/1 headers and fixed control DoH response reader.
 * Never follows redirects, decompresses or exposes headers/body to presentation. */
internal object DiagnosticsHttp {
    private const val STATUS_LINE_BYTES = 1024
    private const val HEADER_LINE_BYTES = 16 * 1024
    private const val TOTAL_HEADER_BYTES = 64 * 1024
    class MalformedResponse : IOException("DIAGNOSTIC_HTTP_PROTOCOL")
    class DnsHttpException(val status: Int) : IOException("DIAGNOSTIC_DOH_HTTP_$status")
    fun line(socket: Socket, token: DiagnosticCancellation, limit: Int = 1024): String {
        val bytes = ByteArrayOutputStream()
        var carriage = false
        while (bytes.size() < limit) {
            token.check(); socket.soTimeout = token.remainingMs()
            val value = socket.inputStream.read()
            if (value < 0) throw MalformedResponse()
            if (value == 10) {
                val result = bytes.toByteArray()
                if (result.isEmpty() || result.last() != 13.toByte()) throw MalformedResponse()
                return result.copyOf(result.size - 1).toString(Charsets.US_ASCII)
            }
            if (carriage) throw MalformedResponse()
            if (value != 13 && value !in 32..126) throw MalformedResponse()
            carriage = value == 13
            bytes.write(value)
        }
        throw MalformedResponse()
    }
    fun status(socket: Socket, token: DiagnosticCancellation): Int =
        Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: .*|)").matchEntire(line(socket, token))?.groupValues?.get(1)?.toInt() ?: throw MalformedResponse()

    data class Head(val status: Int, val headers: Map<String, String>)

    /** Consume complete headers and bounded informational replies. A status line
     * alone, a protocol upgrade or truncated headers are not a valid response. */
    fun head(socket: Socket, token: DiagnosticCancellation): Head {
        // Wire-byte limits include CRLF and span all informational responses.
        var remaining = TOTAL_HEADER_BYTES
        fun boundedLine(maximum: Int): String {
            val budget = minOf(maximum, remaining)
            if (budget < 2) throw MalformedResponse()
            val text = line(socket, token, budget)
            remaining -= text.length + 2
            return text
        }
        repeat(6) {
            val statusLine = boundedLine(STATUS_LINE_BYTES)
            val code = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: .*|)").matchEntire(statusLine)
                ?.groupValues?.get(1)?.toInt() ?: throw MalformedResponse()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val text = boundedLine(HEADER_LINE_BYTES)
                if (text.isEmpty()) break
                val key = text.substringBefore(':').lowercase(java.util.Locale.ROOT)
                if (!text.contains(':') || !key.matches(Regex("[!#$%&'*+.^_`|~0-9a-z-]+"))) throw MalformedResponse()
                val value = text.substringAfter(':').trim()
                if (key in headers && key in setOf("content-length", "transfer-encoding", "content-type", "content-encoding")) throw MalformedResponse()
                headers[key] = value
            }
            if (code == 101) throw MalformedResponse()
            if (code >= 200) return Head(code, headers)
        }
        throw MalformedResponse()
    }

    fun dnsBody(socket: Socket, token: DiagnosticCancellation): ByteArray {
        val response = head(socket, token)
        if (response.status != 200) throw DnsHttpException(response.status)
        val headers = response.headers
        if (!headers["content-type"]?.substringBefore(';')?.trim().equals("application/dns-message", true) ||
            headers["content-encoding"]?.let { !it.equals("identity", true) } == true) throw MalformedResponse()
        val output = ByteArrayOutputStream()
        fun bytes(count: Int) {
            if (count < 0 || count > 4096 - output.size()) throw MalformedResponse()
            repeat(count) {
                token.check(); socket.soTimeout = token.remainingMs()
                val value = socket.inputStream.read(); if (value < 0) throw MalformedResponse(); output.write(value)
            }
        }
        val transfer = headers["transfer-encoding"]
        val length = headers["content-length"]
        if (transfer != null && length != null) throw MalformedResponse()
        when {
            transfer?.equals("chunked", true) == true -> {
                var chunks = 0
                while (true) {
                    if (++chunks > 128) throw MalformedResponse()
                    val size = line(socket, token, 64).substringBefore(';').toIntOrNull(16) ?: throw MalformedResponse()
                    if (size == 0) {
                        // Trailers are bounded too; EOF after the zero chunk is incomplete.
                        var trailers = 1024
                        while (true) {
                            val trailer = line(socket, token, minOf(1024, trailers)); trailers -= trailer.length + 2
                            if (trailers < 0) throw MalformedResponse()
                            if (trailer.isEmpty()) break
                            if (!trailer.contains(':')) throw MalformedResponse()
                        }
                        break
                    }
                    bytes(size)
                    if (line(socket, token, 2).isNotEmpty()) throw MalformedResponse()
                }
            }
            transfer != null -> throw MalformedResponse()
            length != null -> bytes(length.toIntOrNull() ?: throw MalformedResponse())
            else -> while (true) {
                token.check(); socket.soTimeout = token.remainingMs()
                val value = socket.inputStream.read(); if (value < 0) break
                if (output.size() == 4096) throw MalformedResponse(); output.write(value)
            }
        }
        return output.toByteArray()
    }
}
