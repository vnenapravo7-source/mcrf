package org.mcrf.client

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Framing models only; JS asset, WebMessagePort/CSP/renderer NOT executed. */
class OpenFluxHttpPageResponseReviewTest {
    private val page = "a".repeat(32); private val native = "b".repeat(32)
    private fun response(bytes: ByteArray, status: Int = 200, headers: List<Pair<String, String>> = emptyList()) =
        OpenFluxHttpIpcResponse.Response(native, status, headers, bytes)
    @Test fun binaryFramesAreMetadataThenAlignedChunksThenOneTerminalAndWipe() {
        val bytes = ByteArray(8193) { (it * 23).toByte() }; val expected = bytes.copyOf()
        val stream = OpenFluxHttpPageResponse.adopt(page, native, response(bytes), false) { true }!!
        val meta = JSONArray(stream.next()!!); assertEquals("meta", meta.getString(0)); assertEquals(page, meta.getString(1))
        assertEquals(8193, meta.getInt(4))
        val hash = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(hash, meta.getString(5))
        val first = JSONArray(stream.next()!!); val last = JSONArray(stream.next()!!)
        assertEquals("chunk", first.getString(0)); assertEquals(0, first.getInt(2)); assertEquals(8192, last.getInt(2))
        assertArrayEquals(expected, Base64.getDecoder().decode(first.getString(3)) + Base64.getDecoder().decode(last.getString(3)))
        assertEquals("end", JSONArray(stream.next()!!).getString(0)); assertNull(stream.next()); assertTrue(bytes.all { it == 0.toByte() })
        stream.close(); expected.fill(0)
    }
    @Test fun emptyBodyHasMetadataAndEndWithoutInventedDataChunk() {
        val stream = OpenFluxHttpPageResponse.adopt(page, native, response(byteArrayOf(), 204), false) { true }!!
        assertEquals(0, JSONArray(stream.next()!!).getInt(4)); assertEquals("end", JSONArray(stream.next()!!).getString(0))
        assertNull(stream.next())
    }
    @Test fun wrongScopeStatusHeadAndSizeWipeAttemptedResponse() {
        val bytes = byteArrayOf(1)
        assertNull(OpenFluxHttpPageResponse.adopt(page, "c".repeat(32), response(bytes), false) { true }); assertEquals(0.toByte(), bytes[0])
        for (head in listOf(false, true)) {
            val forbidden = byteArrayOf(1)
            assertNull(OpenFluxHttpPageResponse.adopt(page, native, response(forbidden, if (head) 200 else 204), head) { true })
            assertEquals(0.toByte(), forbidden[0])
        }
        val huge = ByteArray(524289) { 1 }
        assertNull(OpenFluxHttpPageResponse.adopt(page, native, response(huge), false) { true }); assertTrue(huge.all { it == 0.toByte() })
    }
    @Test fun ownerLossBetweenFramesClosesWithoutLateTerminalOrBody() {
        var alive = true; val bytes = byteArrayOf(1, 2)
        val stream = OpenFluxHttpPageResponse.adopt(page, native, response(bytes), false) { alive }!!
        assertNotNull(stream.next()); alive = false; assertNull(stream.next()); assertTrue(bytes.all { it == 0.toByte() })
        alive = true; assertNull(stream.next())
    }
    @Test fun nativeSetCookieIsNotRelayedAndCompressedDataIsNotPretendedDecoded() {
        val stream = OpenFluxHttpPageResponse.adopt(page, native,
            response(byteArrayOf(), headers = listOf("Set-Cookie" to "synthetic=1", "X-Test" to "yes")), false) { true }!!
        val headers = JSONArray(stream.next()!!).getJSONArray(3)
        assertEquals(1, headers.length()); assertEquals("X-Test", headers.getJSONArray(0).getString(0)); stream.close()
        val bytes = byteArrayOf(1)
        assertNull(OpenFluxHttpPageResponse.adopt(page, native, response(bytes, headers = listOf("Content-Encoding" to "gzip")), false) { true })
        assertEquals(0.toByte(), bytes[0])
    }
    @Test fun maximumBinaryResponseNeverUsesOneHugePortMessage() {
        val bytes = ByteArray(524288) { 0xff.toByte() }
        val stream = OpenFluxHttpPageResponse.adopt(page, native, response(bytes), false) { true }!!
        assertNotNull(stream.next()); var chunks = 0
        while (true) {
            val message = stream.next()!!; assertTrue(message.length <= 12000)
            val value = JSONArray(message)
            if (value.getString(0) == "end") break
            assertEquals(chunks * 8192, value.getInt(2)); chunks++
        }
        assertEquals(64, chunks); assertTrue(bytes.all { it == 0.toByte() }); assertNull(stream.next())
    }
    @Test fun throwingOwnerRejectsAndRedactedStateDoesNotLeakBody() {
        val bytes = byteArrayOf(1)
        assertNull(OpenFluxHttpPageResponse.adopt(page, native, response(bytes), false) { error("synthetic") })
        assertEquals(0.toByte(), bytes[0])
        val stream = OpenFluxHttpPageResponse.adopt(page, native, response(byteArrayOf()), false) { true }!!
        assertEquals("OpenFluxHttpPageResponse(redacted)", stream.toString()); stream.close()
    }
}
