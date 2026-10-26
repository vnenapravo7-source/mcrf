package org.mcrf.client

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Pure response/URI models, not a Binder client/service acceptance receipt. */
class OpenFluxHttpIpcResponseReviewTest {
    private val id = "a".repeat(32)
    private fun reader(body: ByteArray, current: () -> Boolean = { true }, hash: String = digest(body)) =
        OpenFluxHttpIpcResponse(id, 200, emptyList(), body.size, hash, current)
    @Test fun responseRequiresCompleteBinaryHashAndExactLastFlag() {
        val body = ByteArray(8193) { it.toByte() }; val read = reader(body)
        assertNull(read.take(id))
        assertTrue(read.append(id, 0, body.copyOfRange(0, 8192), false)); assertEquals(8192, read.nextOffset())
        assertNull(read.take(id))
        assertTrue(read.append(id, 8192, body.copyOfRange(8192, 8193), true))
        val result = read.take(id)!!; assertArrayEquals(body, result.body); result.close()
    }
    @Test fun staleScopeCannotCloseOrAdvanceCurrentAssembly() {
        val read = reader(byteArrayOf(7))
        assertFalse(read.append("b".repeat(32), 0, byteArrayOf(3), true)); assertEquals(0, read.nextOffset())
        assertNull(read.take("b".repeat(32)))
        assertTrue(read.append(id, 0, byteArrayOf(7), true)); read.take(id)!!.close()
    }
    @Test fun malformedOrderSizeOrTerminalFlagClosesWithoutPartialExposure() {
        for (mode in listOf("offset", "size", "last")) {
            val read = reader(byteArrayOf(7))
            val accepted = when (mode) {
                "offset" -> read.append(id, 8192, byteArrayOf(7), true)
                "size" -> read.append(id, 0, byteArrayOf(), true)
                else -> read.append(id, 0, byteArrayOf(7), false)
            }
            assertFalse(accepted); assertNull(read.take(id)); assertFalse(read.append(id, 0, byteArrayOf(7), true))
        }
    }
    @Test fun digestMismatchAndEmptyBodyUseSameTerminalVerification() {
        val bad = reader(byteArrayOf(7), hash = "b".repeat(64))
        assertFalse(bad.append(id, 0, byteArrayOf(7), true)); assertNull(bad.take(id))
        val empty = reader(byteArrayOf()); assertNull(empty.take(id))
        assertTrue(empty.append(id, 0, byteArrayOf(), true)); assertEquals(0, empty.take(id)!!.body.size)
        assertNull(empty.take(id))
    }
    @Test fun lostOrThrowingOwnerNeverReturnsCompletedPrivateBody() {
        var live = true; val read = reader(byteArrayOf(7), { live })
        assertTrue(read.append(id, 0, byteArrayOf(7), true)); live = false
        assertNull(read.take(id)); assertFalse(read.append(id, 0, byteArrayOf(7), true))
        val failed = reader(byteArrayOf(7), { throw IllegalStateException("synthetic-private") })
        assertFalse(failed.append(id, 0, byteArrayOf(7), true)); assertNull(failed.take(id))
    }
    @Test fun invalidMetadataCannotAllocateAnUnboundedResponse() {
        for (size in listOf(-1, 524289)) {
            try { OpenFluxHttpIpcResponse(id, 200, emptyList(), size, "b".repeat(64)) { true }; fail("size bound") }
            catch (_: IllegalArgumentException) { }
        }
        for (status in listOf(199, 600, 204, 304)) {
            try { OpenFluxHttpIpcResponse(id, status, emptyList(), 1, "b".repeat(64)) { true }; fail("status/body bound") }
            catch (_: IllegalArgumentException) { }
        }
        try { OpenFluxHttpIpcResponse(id, 200, emptyList(), 0, "bad") { true }; fail("hash bound") }
        catch (_: IllegalArgumentException) { }
        try { OpenFluxHttpIpcResponse(id, 200, List(16) { "x".repeat(800) to "" }, 0, "b".repeat(64)) { true }; fail("serialized header bound") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun privateAddressRequiresCanonicalLiteralLoopbackAndExplicitPort() {
        assertNotNull(OpenFluxHttpIpc.address("http://127.0.0.1:4321/path?sample=private"))
        for (value in listOf("https://127.0.0.1:4321/", "http://localhost:4321/", "http://127.1:4321/",
            "http://[::1]:4321/", "http://127.0.0.1:04321/", "http://user@127.0.0.1:4321/",
            "http://127.0.0.1:4321/#x", "http://127.0.0.1/", "http://127.0.0.1:0/", "http://127.0.0.1:65536/",
            "http://127.0.0.1:4321/a\nb", "http://127.0.0.1:4321/\\path"))
            assertNull(OpenFluxHttpIpc.address(value))
    }
    @Test fun completedOwnershipIsOneShotRedactedAndNotRevocableAfterHandoff() {
        assertEquals(1_000_000L, OpenFluxHttpIpc.deadline(null, 0L, 1L)!!)
        assertEquals(0L, OpenFluxHttpIpc.deadline(0L, 500L, 1L)!!) // Zero is a real old deadline, not an unset sentinel.
        assertEquals(100L, OpenFluxHttpIpc.deadline(100L, 500L, 150000L)!!)
        assertNull(OpenFluxHttpIpc.deadline(null, 0L, 0L)); assertNull(OpenFluxHttpIpc.deadline(null, 0L, 150001L))
        assertNull(OpenFluxHttpIpc.deadline(null, Long.MAX_VALUE - 1, 1L))
        val read = reader(byteArrayOf(7)); assertEquals("OpenFluxHttpIpcResponse(redacted)", read.toString())
        assertTrue(read.append(id, 0, byteArrayOf(7), true)); val result = read.take(id)!!
        read.close(); assertEquals(7.toByte(), result.body[0]); assertNull(read.take(id))
        assertEquals("OpenFluxHttpClientResponse(redacted)", result.toString())
        result.close(); assertEquals(0.toByte(), result.body[0])
    }
    companion object {
        private fun digest(body: ByteArray) = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
