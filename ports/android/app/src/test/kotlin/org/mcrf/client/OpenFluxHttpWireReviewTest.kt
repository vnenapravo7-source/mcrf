package org.mcrf.client

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenFluxHttpWireReviewTest {
    private val id = "a".repeat(32)
    private val request = "b".repeat(32)
    private val emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private fun frame(prefix: String, data: JSONObject) = (prefix + data).toByteArray(Charsets.UTF_8)
    private fun identity() = JSONObject().put("id", id).put("request", request)
    private fun meta() = identity().put("status", 200).put("headers", JSONArray())
        .put("bytes", 1).put("sha256", "c".repeat(64))
    private fun chunk() = identity().put("offset", 0).put("data", "Zg==")
    private fun pair(name: Any, value: Any) = JSONArray().put(name).put(value)
    private fun headers(value: JSONArray) = frame(OpenFluxHttpWire.META, meta().put("headers", value))

    @Test fun reservedFamilyCannotEnterDiagnosticsEvenMalformedClosedOrOversized() {
        var rejected = 0
        for (raw in listOf("MCRF_OPENFLUX_HTTP".toByteArray(), "MCRF_OPENFLUX_HTTP_BROKEN ".toByteArray() + byteArrayOf(-1),
            "MCRF_OPENFLUX_HTTP_RESPONSE ".toByteArray() + ByteArray(32768) { 65 })) {
            assertTrue(OpenFluxHttpWire.discardDisabled(raw, false) { rejected++ })
            assertTrue(OpenFluxHttpWire.discardDisabled(raw, true) { fail("closed child must not reject again") })
        }
        assertEquals(3, rejected)
    }
    @Test fun ordinaryDiagnosticsAreNotSwallowed() {
        for (raw in listOf("ordinary", "MCRF_OPENFLUX_DOC_META {}", "MCRF_OPENFLUX_HTT", "xMCRF_OPENFLUX_HTTP"))
            assertFalse(OpenFluxHttpWire.discardDisabled(raw.toByteArray(), false) { fail("not private HTTP") })
    }
    @Test fun pendingContainsOnlyTypedOpaqueIdentity() {
        fun data() = JSONObject().put("id", id).put("kind", "own_http")
        val good = OpenFluxHttpWire.pending(frame(OpenFluxHttpWire.PENDING, data()))!!
        assertEquals(id, good.id); assertEquals("OpenFluxHttpPending(redacted)", good.toString())
        for (bad in listOf(data().put("url", "synthetic-private"), data().put("kind", "inline"),
            data().put("id", true), data().put("headers", JSONArray()), data().put("html", "private")))
            assertNull(OpenFluxHttpWire.pending(frame(OpenFluxHttpWire.PENDING, bad)))
    }
    @Test fun metadataAcceptsBoundedBinaryAndEmptyResponseWithoutPrintingSecrets() {
        val parsed = OpenFluxHttpWire.metadata(headers(JSONArray().put(pair("Set-Cookie", "synthetic-private"))))!!
        assertEquals(id, parsed.id); assertEquals(request, parsed.request); assertEquals(200, parsed.status)
        assertEquals(1, parsed.bytes); assertEquals("synthetic-private", parsed.headers.single().value)
        assertEquals("OpenFluxHttpMetadata(redacted)", parsed.toString())
        assertEquals("OpenFluxHttpHeader(redacted)", parsed.headers.single().toString())
        for (status in listOf(200, 204, 304, 599)) assertNotNull(OpenFluxHttpWire.metadata(frame(OpenFluxHttpWire.META,
            meta().put("bytes", 0).put("sha256", emptySha).put("status", status))))
        assertNotNull(OpenFluxHttpWire.metadata(frame(OpenFluxHttpWire.META, meta().put("bytes", 524288))))
    }
    @Test fun metadataRejectsWrongTypesLimitsIdentitiesAndNoBodyStatuses() {
        for (bad in listOf(meta().put("status", 199), meta().put("status", 600), meta().put("status", "200"),
            meta().put("status", 204), meta().put("status", 304), meta().put("bytes", -1), meta().put("bytes", 524289),
            meta().put("bytes", "1"), meta().put("bytes", 0), meta().put("id", id.uppercase()),
            meta().put("request", true), meta().put("sha256", "BAD"), meta().put("url", "private")))
            assertNull(OpenFluxHttpWire.metadata(frame(OpenFluxHttpWire.META, bad)))
        assertNull(OpenFluxHttpWire.metadata(frame(OpenFluxHttpWire.META, meta()).toString(Charsets.UTF_8)
            .replace("\"bytes\":1", "\"bytes\":1.0").toByteArray()))
    }
    @Test fun headersRejectHopFramingControlsInvalidShapeAndAggregateExcess() {
        val invalid = mutableListOf(JSONArray().put("not-pair"), JSONArray().put(pair("x", true)),
            JSONArray().put(JSONArray().put("x")), JSONArray().put(pair("bad name", "v")),
            JSONArray().put(pair("x", "a\nb")), JSONArray().put(pair("x", "a\u007fb")),
            JSONArray().put(pair("x", "v".repeat(8190))))
        for (name in listOf("Connection", "Proxy-Authorization", "Proxy-Authenticate", "Keep-Alive", "TE",
            "Trailer", "Transfer-Encoding", "Upgrade", "Host", "Content-Length")) invalid.add(JSONArray().put(pair(name, "v")))
        invalid.add(JSONArray().also { a -> repeat(33) { a.put(pair("x-$it", "v")) } })
        invalid.add(JSONArray().also { a -> repeat(17) { a.put(pair(if (it % 2 == 0) "X" else "x", "v")) } })
        for (bad in invalid) assertNull(OpenFluxHttpWire.metadata(headers(bad)))
        val escapedSurrogate = headers(JSONArray().put(pair("x", "SURROGATE"))).toString(Charsets.UTF_8)
            .replace("SURROGATE", "\\uD800").toByteArray(Charsets.UTF_8)
        assertNull(OpenFluxHttpWire.metadata(escapedSurrogate))
        assertNotNull(OpenFluxHttpWire.metadata(headers(JSONArray().put(pair("x", "a\tb")))))
    }
    @Test fun canonicalChunksOwnWipeableBytesAndRedactedIdentity() {
        val parsed = OpenFluxHttpWire.chunk(frame(OpenFluxHttpWire.CHUNK, chunk()))!!
        assertEquals(id, parsed.id); assertEquals(request, parsed.request); assertEquals(0, parsed.offset)
        assertArrayEquals(byteArrayOf(102), parsed.bytes); assertEquals("OpenFluxHttpChunk(redacted)", parsed.toString())
        parsed.close(); assertTrue(parsed.bytes.all { it == 0.toByte() })
        val max = OpenFluxHttpWire.chunk(frame(OpenFluxHttpWire.CHUNK, chunk().put("offset", 516096)
            .put("data", Base64.getEncoder().encodeToString(ByteArray(8192) { 65 }))))!!
        assertEquals(8192, max.bytes.size); max.close()
    }
    @Test fun chunksRejectNoncanonicalEncodingOffsetsAndCoercion() {
        for (offset in listOf<Any>(-1, 1, 524288, "0", true))
            assertNull(OpenFluxHttpWire.chunk(frame(OpenFluxHttpWire.CHUNK, chunk().put("offset", offset))))
        for (value in listOf<Any>("", "Zg", "Zh==", "Zg==\n", true, Base64.getEncoder().encodeToString(ByteArray(8193))))
            assertNull(OpenFluxHttpWire.chunk(frame(OpenFluxHttpWire.CHUNK, chunk().put("data", value))))
        for (bad in listOf(chunk().put("id", "bad"), chunk().put("request", "bad"), chunk().put("extra", true)))
            assertNull(OpenFluxHttpWire.chunk(frame(OpenFluxHttpWire.CHUNK, bad)))
    }
    @Test fun malformedUtf8NestingTrailingDataAndOversizedRecordsAreRejected() {
        val good = frame(OpenFluxHttpWire.META, meta())
        for (tail in listOf(" trailing".toByteArray(), "\u0000 trailing".toByteArray(), byteArrayOf(-1)))
            assertNull(OpenFluxHttpWire.metadata(good + tail))
        assertNull(OpenFluxHttpWire.metadata((OpenFluxHttpWire.META + "[".repeat(17) + "0" + "]".repeat(17)).toByteArray()))
        assertNull(OpenFluxHttpWire.metadata(good + ByteArray(24577) { 32 }))
        assertNull(OpenFluxHttpWire.chunk(frame(OpenFluxHttpWire.CHUNK, chunk()) + " trailing".toByteArray()))
    }
    @Test fun endRequiresBothExactOpaqueIdentitiesAndNoAdditionalData() {
        val good = frame(OpenFluxHttpWire.END, identity())
        val parsed = OpenFluxHttpWire.end(good)!!
        assertEquals(id, parsed.id); assertEquals(request, parsed.request); assertEquals("OpenFluxHttpEnd(redacted)", parsed.toString())
        for (bad in listOf(identity().put("request", true), identity().put("id", "bad"), identity().put("html", "private")))
            assertNull(OpenFluxHttpWire.end(frame(OpenFluxHttpWire.END, bad)))
        assertNull(OpenFluxHttpWire.end(good + " trailing".toByteArray()))
    }
    @Test fun openedBindsOpaquePermitToPrivateCanonicalLoopbackAddress() {
        val data = identity().put("permit", "d".repeat(32)).put("address", "http://127.0.0.1:4321/path?sample=private")
        val parsed = OpenFluxHttpWire.opened(frame(OpenFluxHttpWire.OPENED, data))!!
        assertEquals(id, parsed.id); assertEquals(request, parsed.request); assertEquals("d".repeat(32), parsed.permit)
        assertEquals("OpenFluxHttpOpened(redacted)", parsed.toString())
    }
    @Test fun openedRejectsForeignOriginsProxyAmbiguityAndMalformedPermits() {
        fun data() = identity().put("permit", "d".repeat(32)).put("address", "http://127.0.0.1:4321/path")
        for (address in listOf("https://127.0.0.1:4321/", "http://localhost:4321/", "http://127.1:4321/",
            "http://127.0.0.1/", "http://127.0.0.1:04321/", "http://user@127.0.0.1:4321/", "http://127.0.0.1:4321/#x",
            "http://127.0.0.1:0/", "http://127.0.0.1:65536/", "http://127.0.0.1:4321/a\nb", "http://127.0.0.1:4321/" + "x".repeat(8192)))
            assertNull(OpenFluxHttpWire.opened(frame(OpenFluxHttpWire.OPENED, data().put("address", address))))
        for (bad in listOf(data().put("permit", "bad"), data().put("request", true), data().put("html", "private")))
            assertNull(OpenFluxHttpWire.opened(frame(OpenFluxHttpWire.OPENED, bad)))
    }
    @Test fun resultAcceptsOnlyFiniteCodesAndExactIdentityWithoutPrivateText() {
        for (code in listOf("open_failed", "request_failed", "response_rejected", "reply_failed", "accepted")) {
            val parsed = OpenFluxHttpWire.result(frame(OpenFluxHttpWire.RESULT, identity().put("code", code)))!!
            assertEquals(code, parsed.code); assertEquals("OpenFluxHttpResult(redacted)", parsed.toString())
        }
        for (bad in listOf(identity().put("code", "private server error"), identity().put("code", true),
            identity().put("code", "accepted").put("address", "private")))
            assertNull(OpenFluxHttpWire.result(frame(OpenFluxHttpWire.RESULT, bad)))
    }
}
