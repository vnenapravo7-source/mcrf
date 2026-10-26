package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Memory-only delivery models, not Android Parcel/remote-renderer acceptance. */
class OpenFluxDocumentDeliveryReviewTest {
    private fun source(delivery: OpenFluxDocumentDelivery): ByteArray =
        OpenFluxDocumentDelivery::class.java.getDeclaredField("source").apply { isAccessible = true }.get(delivery) as ByteArray

    @Test fun unicodeSplitChunksOnlyDecodeAfterCompleteHashAndUtf8Check() {
        val html = "x".repeat(8191) + "я<html>synthetic</html>"
        val delivery = OpenFluxDocumentDelivery(html); val owned = source(delivery)
        val id = "a".repeat(32)
        val receiver = OpenFluxOwnDocument(id, id, delivery.total, delivery.sha256) { true }
        val first = delivery.read(0)!!
        assertTrue(receiver.append(id, id, first.offset, first.bytes)); first.close()
        assertNull(receiver.take(id, id))
        val second = delivery.read(8192)!!
        assertTrue(receiver.append(id, id, second.offset, second.bytes)); second.close()
        assertEquals(html, receiver.take(id, id)); assertTrue(owned.all { it == 0.toByte() })
        assertNull(delivery.read(0)); assertEquals("OpenFluxDocumentDelivery(redacted)", delivery.toString())
    }

    @Test fun maximumDocumentNeedsExactly64BoundedDefensiveChunks() {
        val delivery = OpenFluxDocumentDelivery("x".repeat(512 * 1024)); val owned = source(delivery)
        repeat(64) { index ->
            val chunk = delivery.read(index * 8192)!!
            assertEquals(8192, chunk.bytes.size); assertEquals(delivery.total, chunk.total)
            assertEquals(delivery.sha256, chunk.sha256); chunk.close()
            assertTrue(chunk.bytes.all { it == 0.toByte() })
        }
        assertTrue(owned.all { it == 0.toByte() }); assertNull(delivery.read(512 * 1024))
    }

    @Test fun outOfOrderOrDuplicateReadClosesOwnedBufferRatherThanReplaying() {
        for (duplicate in listOf(false, true)) {
            val delivery = OpenFluxDocumentDelivery("x".repeat(9000)); val owned = source(delivery)
            if (duplicate) delivery.read(0)!!.close()
            assertNull(delivery.read(if (duplicate) 0 else 8192))
            assertTrue(owned.all { it == 0.toByte() }); assertNull(delivery.read(if (duplicate) 8192 else 0))
        }
    }

    @Test fun returnedChunkDoesNotAliasRetainedSourceAndCloseDropsFutureReads() {
        val delivery = OpenFluxDocumentDelivery("x".repeat(9000)); val owned = source(delivery)
        val chunk = delivery.read(0)!!; chunk.bytes.fill(0)
        assertEquals('x'.code.toByte(), owned[0])
        delivery.close(); delivery.close()
        assertTrue(owned.all { it == 0.toByte() }); assertNull(delivery.read(8192)); chunk.close()
    }

    @Test fun emptyOversizeUtf8AndMalformedSurrogatesAreNotDeliverable() {
        for (html in listOf("", "x".repeat(512 * 1024 + 1), "я".repeat(300000), "broken\ud800")) {
            var refused = false
            try { OpenFluxDocumentDelivery(html).close() } catch (_: Exception) { refused = true }
            assertTrue(refused)
        }
    }
}
