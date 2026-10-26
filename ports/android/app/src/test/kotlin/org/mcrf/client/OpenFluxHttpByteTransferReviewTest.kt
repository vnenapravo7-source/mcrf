package org.mcrf.client

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Pure byte ownership/codec models; real Parcel/Binder checks remain separate. */
class OpenFluxHttpByteTransferReviewTest {
    private val id = "a".repeat(32)
    private fun response(body: ByteArray, status: Int = 200, headers: List<OpenFluxHttpWire.Header> = emptyList()) =
        OpenFluxHttpSession.Response(id, status, headers, body)
    private fun json(pairs: List<Pair<String, String>>): String {
        val array = JSONArray(); pairs.forEach { array.put(JSONArray().put(it.first).put(it.second)) }; return array.toString()
    }
    @Test fun uploadAcceptsOnlyExactSequentialChunksAndTransfersOnce() {
        val upload = OpenFluxHttpUpload(id, 65536) { true }
        assertFalse(upload.append("b".repeat(32), 0, ByteArray(8192)))
        repeat(8) { offset ->
            assertTrue(upload.append(id, offset * 8192, ByteArray(8192) { offset.toByte() }))
            if (offset != 7) assertNull(upload.take(id))
        }
        val body = upload.take(id)!!
        assertEquals(65536, body.size); repeat(8) { assertEquals(it.toByte(), body[it * 8192]) }
        assertNull(upload.take(id)); upload.close(); assertEquals(7.toByte(), body.last())
        body.fill(0); assertEquals("OpenFluxHttpUpload(redacted)", upload.toString())
    }
    @Test fun uploadMalformedOrderOrLostOwnerIsTerminalAndEmptyIsOneShot() {
        val upload = OpenFluxHttpUpload(id, 8193) { true }
        assertTrue(upload.append(id, 0, ByteArray(8192)))
        assertFalse(upload.append(id, 8192, ByteArray(2))); assertNull(upload.take(id))
        var live = true; val stale = OpenFluxHttpUpload(id, 1) { live }
        live = false; assertFalse(stale.append(id, 0, byteArrayOf(7))); assertNull(stale.take(id))
        val empty = OpenFluxHttpUpload(id, 0) { true }; assertEquals(0, empty.take(id)!!.size); assertNull(empty.take(id))
    }
    @Test fun uploadSizeIdentityAndCallbackFailureStayBounded() {
        for (size in listOf(-1, 65537)) {
            try { OpenFluxHttpUpload(id, size) { true }; fail("upload bound") } catch (_: IllegalArgumentException) { }
        }
        try { OpenFluxHttpUpload("bad", 0) { true }; fail("identity bound") } catch (_: IllegalArgumentException) { }
        val upload = OpenFluxHttpUpload(id, 1) { throw IllegalStateException("synthetic-private") }
        assertFalse(upload.append(id, 0, byteArrayOf(1))); assertNull(upload.take(id))
    }
    @Test fun deliveryMetadataThenBinaryChunksWipeSourceOnLastButNotAdmittedCopy() {
        val body = ByteArray(8193) { it.toByte() }; val expected = body.copyOf()
        val delivery = OpenFluxHttpResponseDelivery.adopt(response(body)) { true }!!
        val metadata = delivery.metadata(id)!!
        assertEquals(8193, metadata.total); assertEquals(64, metadata.sha256.length); assertEquals("[]", metadata.headersJson)
        val first = delivery.read(id, 0)!!; assertFalse(first.last); assertEquals(8192, first.bytes.size)
        val last = delivery.read(id, 8192)!!; assertTrue(last.last); assertEquals(1, last.bytes.size)
        assertArrayEquals(expected, first.bytes + last.bytes)
        assertTrue(body.all { it == 0.toByte() }); assertNull(delivery.metadata(id)); assertNull(delivery.read(id, 8192))
        first.close(); last.close(); assertTrue(first.bytes.all { it == 0.toByte() })
    }
    @Test fun deliveryRequiresMetadataAndClosesOnOffsetOrOwnerFailure() {
        for (mode in listOf("metadata", "offset", "owner", "exception")) {
            val body = byteArrayOf(7); var live = true
            val delivery = OpenFluxHttpResponseDelivery.adopt(response(body)) {
                if (mode == "exception") throw IllegalStateException("synthetic-private"); live
            }!!
            if (mode != "metadata") delivery.metadata(id)
            if (mode == "owner") live = false
            assertNull(delivery.read(id, if (mode == "offset") 8192 else 0))
            assertEquals(0.toByte(), body[0]); assertNull(delivery.metadata(id))
        }
    }
    @Test fun deliveryWrongRequestCannotPoisonCurrentAndEmptyHasOneTerminalChunk() {
        val delivery = OpenFluxHttpResponseDelivery.adopt(response(byteArrayOf())) { true }!!
        assertNull(delivery.metadata("b".repeat(32))); assertNull(delivery.read("b".repeat(32), 0))
        assertEquals(0, delivery.metadata(id)!!.total)
        val chunk = delivery.read(id, 0)!!; assertTrue(chunk.last); assertEquals(0, chunk.bytes.size)
        assertNull(delivery.read(id, 0)); chunk.close()
    }
    @Test fun failedDeliveryAdoptionWipesBodyForInvalidStatusAndMetadataBounds() {
        val repeated = List(16) { OpenFluxHttpWire.Header("x".repeat(800), "") }
        for (value in listOf(response(byteArrayOf(7), 199), response(byteArrayOf(7), 204),
            response(byteArrayOf(7), headers = listOf(OpenFluxHttpWire.Header("Host", "foreign"))),
            response(byteArrayOf(7), headers = repeated), response(ByteArray(524289) { 7 }))) {
            val body = value.body
            assertNull(OpenFluxHttpResponseDelivery.adopt(value) { true }); assertTrue(body.all { it == 0.toByte() })
        }
    }
    @Test fun headerCodecRejectsShapeCoercionUtf16AndTrailingGarbage() {
        for (text in listOf("{}", "null", "[null]", "[[\"x\"]]", "[[\"x\",null]]", "[[\"x\",1]]",
            "[[\"x\",\"\\uD800\"]]", "[] trailing", "[]\u0000", "[".repeat(17) + "0" + "]".repeat(17)))
            assertNull(OpenFluxHttpIpc.headers(text))
        assertEquals(emptyList<Pair<String, String>>(), OpenFluxHttpIpc.headers("[]"))
    }
    @Test fun flattenedAndEscapedHeadersHaveIndependentParcelSafeBudget() {
        val repeated = List(16) { "x".repeat(800) to "" }
        assertTrue(OpenFluxHttpWire.headersAllowed(repeated)); assertNull(OpenFluxHttpIpc.encodeHeaders(repeated))
        val escaped = listOf("x" to "\"".repeat(7000))
        assertTrue(OpenFluxHttpWire.headersAllowed(escaped)); assertNull(OpenFluxHttpIpc.encodeHeaders(escaped))
        assertNull(OpenFluxHttpIpc.headers(json(List(17) { "x" to "v" })))
        assertNull(OpenFluxHttpIpc.headers(json((1..33).map { "x-$it" to "v" })))
        assertNull(OpenFluxHttpIpc.headers(json(listOf("Transfer-Encoding" to "chunked"))))
    }
    @Test fun metadataIsSeparatePrivateRedactedAndHeaderRoundTripNeverTruncates() {
        val pairs = listOf("X-Sample" to "synthetic-private", "Set-Cookie" to "sample=private")
        val encoded = OpenFluxHttpIpc.encodeHeaders(pairs)!!
        assertEquals(pairs, OpenFluxHttpIpc.headers(encoded))
        assertTrue(encoded.length <= OpenFluxHttpIpc.MAX_HEADER_JSON)
        val delivery = OpenFluxHttpResponseDelivery.adopt(response(byteArrayOf(7), headers = pairs.map { OpenFluxHttpWire.Header(it.first, it.second) })) { true }!!
        val metadata = delivery.metadata(id)!!
        assertEquals(encoded, metadata.headersJson)
        assertEquals("OpenFluxHttpResponseMetadata(redacted)", metadata.toString())
        assertEquals("OpenFluxHttpResponseDelivery(redacted)", delivery.toString())
        val chunk = delivery.read(id, 0)!!; assertEquals("OpenFluxHttpResponseChunk(redacted)", chunk.toString()); chunk.close()
    }
}
