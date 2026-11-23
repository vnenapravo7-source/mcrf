package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class TrafficDomainAttributionTest {
    private fun http(value: String) = TrafficDomainAttribution.fromPrefix(value.toByteArray(Charsets.US_ASCII))
    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun hello(host: String, extra: ByteArray = byteArrayOf()): ByteArray {
        val name = host.toByteArray(Charsets.US_ASCII)
        val item = byteArrayOf(0) + u16(name.size) + name
        val sni = u16(0) + u16(item.size + 2) + u16(item.size) + item
        val extensions = sni + extra
        val body = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0) + u16(2) +
            byteArrayOf(0x13, 1, 1, 0) + u16(extensions.size) + extensions
        val handshake = byteArrayOf(1, 0, (body.size ushr 8).toByte(), body.size.toByte()) + body
        return byteArrayOf(22, 3, 3) + u16(handshake.size) + handshake
    }
    @Test fun extractsOnlyHostWithoutRequestTargetOrOtherHeaders() {
        val result = http("GET /private?secret=never-save HTTP/1.1\r\nAuthorization: secret\r\nHoSt: EXAMPLE.ORG.:443\r\n\r\nbody")!!
        assertEquals("example.org", result.domain)
        assertEquals(TrafficJournalStore.DomainSource.HTTP_HOST, result.source)
        assertFalse(result.toString().contains("secret")); assertFalse(result.toString().contains("body"))
    }
    @Test fun rejectsAmbiguousUnfinishedInvalidAndOversizeHttp() {
        for (value in listOf(
            "GET / HTTP/1.1\r\nHost: a.example\r\nHost: b.example\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: a.example\r\n folded\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: a.example\r\n",
            "GET / HTTP/1.1\r\nHost: a.example:99999\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: user@a.example\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: 192.0.2.1\r\n\r\n",
            "GET http://other.example/ HTTP/1.1\r\nHost: a.example\r\n\r\n",
            "GET / HTTP/1.1\r\nHost : other.example\r\nHost: a.example\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: a.example\r\n\r\n" + "x".repeat(4096)
        )) assertNull(value.take(80), http(value))
    }
    @Test fun stopsAtHeaderBoundaryBeforeBinaryBody() {
        val header = "POST / HTTP/1.1\r\nHost: a.example\r\n\r\n".toByteArray()
        assertEquals("a.example", TrafficDomainAttribution.fromPrefix(header + byteArrayOf(0, -1, 22))?.domain)
    }
    @Test fun parsesOneCompleteClientHelloAndRejectsAllTruncations() {
        val bytes = hello("WWW.Example.org")
        val result = TrafficDomainAttribution.fromPrefix(bytes)!!
        assertEquals("www.example.org", result.domain)
        assertEquals(TrafficJournalStore.DomainSource.SNI, result.source)
        for (length in 0 until bytes.size) assertNull("length=$length", TrafficDomainAttribution.fromPrefix(bytes, length))
    }
    @Test fun echDuplicateSniAndMalformedLengthRemainUnknown() {
        assertNull(TrafficDomainAttribution.fromPrefix(hello("a.example", u16(0xfe0d) + u16(0))))
        val single = hello("a.example")
        assertNull(TrafficDomainAttribution.fromPrefix(hello("a.example", single.copyOfRange(52, single.size))))
        val damaged = single.clone().apply { this[4] = 1 }
        assertNull(TrafficDomainAttribution.fromPrefix(damaged))
        assertNull(TrafficDomainAttribution.fromPrefix(ByteArray(4097)))
        assertNull(TrafficDomainAttribution.fromPrefix(single, single.size + 1))
    }
    @Test fun hostValidationNeverTreatsUrlsPathsOrNumericIpsAsNames() {
        for (value in listOf("https://example.org", "example.org/path", "user@example.org", "127.0.0.1", "[::1]", "-bad.example", "a..example", "bad\u0000.example"))
            assertNull(value, TrafficDomainAttribution.canonicalHost(value))
        assertEquals("xn--e1afmkfd.xn--p1ai", TrafficDomainAttribution.canonicalHost("XN--E1AFMKFD.XN--P1AI."))
    }
}
