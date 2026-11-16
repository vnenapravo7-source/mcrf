package org.mcrf.client

import java.util.Locale

/** Stateless first-flight parser. No reads, copies of bodies, decryption, reassembly or logging.
 * A caller may supply at most 4 KiB already observed by the transport, once per flow.
 * Incomplete/ambiguous/oversize input stays unknown; never fall through TLS into HTTP. */
object TrafficDomainAttribution {
    const val MAX_PREFIX = 4096
    data class Match(val domain: String, val source: TrafficJournalStore.DomainSource)
    fun fromPrefix(bytes: ByteArray, length: Int = bytes.size): Match? {
        if (length !in 1..MAX_PREFIX || length > bytes.size) return null
        return if (u8(bytes, 0) == 22) tls(bytes, length) else http(bytes, length)
    }
    fun canonicalHost(value: String): String? {
        if (value.length !in 1..253 || value.any { it.code !in 33..126 }) return null
        val name = value.lowercase(Locale.ROOT).removeSuffix(".")
        // Numeric literals are IP metadata, not domain attribution. Never resolve names here.
        if (name.all { it.isDigit() || it == '.' }) return null
        return name.takeIf { it.split('.').all { label ->
            label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        } }
    }
    private fun http(b: ByteArray, end: Int): Match? {
        fun lineEnd(start: Int): Int {
            var p = start
            while (p + 1 < end) {
                if (b[p] == 13.toByte() && b[p + 1] == 10.toByte()) return p
                if (u8(b, p) !in 32..126 && b[p] != 9.toByte()) return -1
                p++
            }
            return -1
        }
        val firstEnd = lineEnd(0).takeIf { it in 14..1024 } ?: return null
        // Check framing without materializing the request target/URL.
        val space = (0 until firstEnd).firstOrNull { b[it] == 32.toByte() } ?: return null
        val methods = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "TRACE")
        if (space !in 3..7 || String(b, 0, space, Charsets.US_ASCII) !in methods) return null
        val versionStart = firstEnd - 9
        if (versionStart <= space || String(b, versionStart, 9, Charsets.US_ASCII) !in setOf(" HTTP/1.0", " HTTP/1.1")) return null
        // Absolute-form proxy targets can disagree with Host; do not guess or parse their URL.
        if (b[space + 1] != 47.toByte() && !(space == 7 && b[space + 1] == 42.toByte() && versionStart == space + 2)) return null
        if ((space + 1 until versionStart).any { u8(b, it) !in 33..126 }) return null
        var p = firstEnd + 2
        var host: String? = null
        var headers = 0
        while (p < end && headers++ < 64) {
            val stop = lineEnd(p).takeIf { it >= p } ?: return null
            if (stop == p) return host?.let { Match(it, TrafficJournalStore.DomainSource.HTTP_HOST) }
            if (b[p] == 32.toByte() || b[p] == 9.toByte()) return null // obsolete folding is ambiguous
            val colon = (p until stop).firstOrNull { b[it] == 58.toByte() } ?: return null
            if (colon == p || (p until colon).any { u8(b, it).toChar() !in HEADER_NAME }) return null
            if (colon - p == 4 && (0..3).all { u8(b, p + it).toChar().lowercaseChar() == "host"[it] }) {
                if (host != null) return null // even identical duplicate Host is rejected
                var start = colon + 1; var last = stop
                while (start < last && (b[start] == 32.toByte() || b[start] == 9.toByte())) start++
                while (last > start && (b[last - 1] == 32.toByte() || b[last - 1] == 9.toByte())) last--
                if (last - start !in 1..259) return null
                val value = String(b, start, last - start, Charsets.US_ASCII)
                val hostPart = if (':' in value) {
                    if (value.count { it == ':' } != 1) return null
                    val port = value.substringAfter(':')
                    if (port.length !in 1..5 || port.any { it !in '0'..'9' } || port.toInt() !in 1..65535) return null
                    value.substringBefore(':')
                } else value
                host = canonicalHost(hostPart) ?: return null
            }
            p = stop + 2
        }
        return null
    }
    private fun tls(b: ByteArray, end: Int): Match? {
        if (end < 9 || u8(b, 1) != 3 || u8(b, 2) !in 1..3 || u8(b, 5) != 1) return null
        val recordEnd = 5 + u16(b, 3)
        if (recordEnd > end || recordEnd < 9) return null
        val helloEnd = 9 + (u8(b, 6) shl 16) + (u8(b, 7) shl 8) + u8(b, 8)
        if (helloEnd != recordEnd || helloEnd < 44) return null
        var p = 43 // legacy version + random, then length-prefixed session ID
        val sessionLength = u8(b, p++)
        if (sessionLength > 32 || p + sessionLength + 2 > helloEnd) return null
        p += sessionLength
        val suites = u16(b, p); p += 2
        if (suites < 2 || suites % 2 != 0 || p + suites + 1 > helloEnd) return null
        p += suites
        val compression = u8(b, p++)
        if (compression == 0 || p + compression + 2 > helloEnd) return null
        p += compression
        val extensions = u16(b, p); p += 2
        if (p + extensions != helloEnd) return null
        var found: String? = null
        var count = 0
        while (p + 4 <= helloEnd && count++ < 64) {
            val type = u16(b, p); val size = u16(b, p + 2); p += 4
            val stop = p + size
            if (stop > helloEnd) return null
            if (type == 0xfe0d) return null // ECH outer name is not the requested destination
            if (type == 0) {
                if (found != null || size < 5 || u16(b, p) != size - 2 || u8(b, p + 2) != 0) return null
                val length = u16(b, p + 3)
                if (length !in 1..253 || length + 5 != size) return null
                found = canonicalHost(String(b, p + 5, length, Charsets.US_ASCII)) ?: return null
            }
            p = stop
        }
        return if (p == helloEnd) found?.let { Match(it, TrafficJournalStore.DomainSource.SNI) } else null
    }
    private fun u8(b: ByteArray, offset: Int) = b[offset].toInt() and 255
    private fun u16(b: ByteArray, offset: Int) = (u8(b, offset) shl 8) or u8(b, offset + 1)
    private const val HEADER_NAME = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
}
