package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class PacketFixtureHttpTest {
    private val body="mcrf-fixture-"+"a".repeat(32)
    private fun response(headers:String="Content-Length: ${body.length}", content:String=body) = "HTTP/1.1 200 OK\r\n$headers\r\n\r\n$content".toByteArray(Charsets.US_ASCII)
    @Test fun readsFramedBodyWithoutWaitingForConnectionClose() {
        val bytes=response(); var offset=0
        val input=object:InputStream() {override fun read():Int {check(offset<bytes.size) {"Must not read beyond Content-Length"};return bytes[offset++].toInt() and 255}}
        PacketFixtureHttp.verify(input,body);assertEquals(bytes.size,offset)
    }
    @Test fun rejectsMissingAmbiguousChunkedAndWrongLength() {
        listOf("X: y","Content-Length: ${body.length}\r\ncontent-length: ${body.length}","Content-Length: ${body.length}\r\nTransfer-Encoding: chunked","Content-Length: 999").forEach {
            assertThrows(IllegalArgumentException::class.java) {PacketFixtureHttp.verify(ByteArrayInputStream(response(it)),body)}
        }
    }
    @Test fun rejectsWrongPayloadAndTruncatedBody() {
        assertThrows(IllegalArgumentException::class.java) {PacketFixtureHttp.verify(ByteArrayInputStream(response(content=body.dropLast(1)+"b")),body)}
        assertThrows(IllegalStateException::class.java) {PacketFixtureHttp.verify(ByteArrayInputStream(response(content=body.dropLast(1))),body)}
    }
    @Test fun boundsHeaderAndRejectsOtherStatus() {
        assertThrows(IllegalArgumentException::class.java) {PacketFixtureHttp.verify(ByteArrayInputStream("x".repeat(4097).toByteArray()),body)}
        assertThrows(IllegalArgumentException::class.java) {PacketFixtureHttp.verify(ByteArrayInputStream(String(response()).replace("200 OK","302 Found").toByteArray()),body)}
    }
}
