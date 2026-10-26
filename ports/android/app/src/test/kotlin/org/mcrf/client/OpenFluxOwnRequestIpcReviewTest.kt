package org.mcrf.client

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exact-request memory queue/receiver tests. NOT actual Binder or user gestures. */
class OpenFluxOwnRequestIpcReviewTest {
    private val child = "a".repeat(32); private val ticket = "b".repeat(32)
    private fun frame(prefix: String, value: JSONObject) = (prefix + value).toByteArray(Charsets.UTF_8)
    private fun register(registry: OpenFluxVerificationRegistry, t: String = ticket, alive: () -> Boolean = { true },
                         offer: (ByteArray) -> Boolean = { true }) =
        registry.registerInline(child, OpenFluxDocumentWire.Pending(t), alive, offer)!!
    private fun stream(request: OpenFluxVerificationRegistry.Request, html: String, end: Boolean = true) {
        val raw = html.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(OpenFluxDocumentReceiver.Result.CONSUMED, request.receiveDocument(child,
            frame(OpenFluxDocumentWire.META, JSONObject().put("id", request.nativeId).put("bytes", raw.size).put("sha256", hash))))
        var offset = 0
        while (offset < raw.size) {
            val bytes = raw.copyOfRange(offset, minOf(offset + 8192, raw.size))
            assertEquals(OpenFluxDocumentReceiver.Result.CONSUMED, request.receiveDocument(child,
                frame(OpenFluxDocumentWire.CHUNK, JSONObject().put("id", request.nativeId).put("offset", offset)
                    .put("data", Base64.getEncoder().encodeToString(bytes)))))
            offset += bytes.size; bytes.fill(0)
        }
        if (end) assertEquals(OpenFluxDocumentReceiver.Result.COMPLETE, request.receiveDocument(child,
            frame(OpenFluxDocumentWire.END, JSONObject().put("id", request.nativeId))))
    }

    @Test fun explicitClaimReadAndCompletePullPrecedeSeparateOneShotOwnReply() {
        val registry = OpenFluxVerificationRegistry(); val commands = mutableListOf<String>(); val borrowed = mutableListOf<ByteArray>()
        val request = register(registry, offer = { borrowed += it; commands += it.toString(Charsets.US_ASCII); true })
        assertFalse(request.requestDocumentAfterUserOpen()); assertTrue(commands.isEmpty())
        assertTrue(registry.claimBrowser(request.id)); assertTrue(request.requestDocumentAfterUserOpen())
        assertFalse(request.requestDocumentAfterUserOpen()); assertEquals("OPENFLUX_DOCUMENT_READ|$ticket\n", commands.single())
        assertFalse(request.submitOwn("{\"answer\":true}"))
        stream(request, "<html>синтетический</html>")
        request.pullDocument(0).chunk!!.close()
        val payload = "{\"client\":{\"answer\":\"я\"}}"
        assertTrue(request.submitOwn(payload)); assertFalse(request.submitOwn(payload)); assertFalse(request.submit("cookie=synthetic"))
        val parts = commands.last().trimEnd().split('|')
        assertEquals("OPENFLUX_DOCUMENT_REPLY", parts[0]); assertEquals(ticket, parts[1])
        assertEquals(payload, Base64.getDecoder().decode(parts[2]).toString(Charsets.UTF_8))
        assertTrue(borrowed.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun rejectedOrThrowingReadOfferDoesNotRearmSameTicket() {
        for (throwing in listOf(false, true)) {
            val registry = OpenFluxVerificationRegistry()
            val request = register(registry, offer = { if (throwing) error("synthetic") else false })
            assertTrue(registry.claimBrowser(request.id)); assertFalse(request.requestDocumentAfterUserOpen())
            assertFalse(request.requestDocumentAfterUserOpen()); assertFalse(request.submitOwn("{\"v\":1}"))
            assertFalse(request.pullDocument(0).waiting); assertNull(request.pullDocument(0).chunk)
            val end = frame(OpenFluxDocumentWire.END, JSONObject().put("id", ticket))
            assertEquals(OpenFluxDocumentReceiver.Result.REJECTED, request.receiveDocument(child, end))
        }
    }

    @Test fun incompleteDocumentWaitsAndFinalSequentialCopyIsNotAnAck() {
        val registry = OpenFluxVerificationRegistry(); val request = register(registry)
        assertTrue(registry.claimBrowser(request.id)); assertTrue(request.requestDocumentAfterUserOpen())
        assertTrue(request.pullDocument(0).waiting)
        stream(request, "x".repeat(9000), end = false); assertTrue(request.pullDocument(0).waiting)
        request.receiveDocument(child, frame(OpenFluxDocumentWire.END, JSONObject().put("id", ticket)))
        val first = request.pullDocument(0).chunk!!; assertEquals(8192, first.bytes.size); first.close()
        assertFalse(request.submitOwn("{\"v\":1}"))
        val last = request.pullDocument(8192).chunk!!; assertEquals(808, last.bytes.size); last.close()
        assertNull(request.pullDocument(8192).chunk); assertFalse(request.submitted.get())
        assertSame(request, registry.get(request.id))
    }

    @Test fun releaseAndReplacementCannotResumeOldPartiallyPulledDocument() {
        val registry = OpenFluxVerificationRegistry(); val old = register(registry)
        assertTrue(registry.claimBrowser(old.id)); assertTrue(old.requestDocumentAfterUserOpen()); stream(old, "x".repeat(9000))
        old.pullDocument(0).chunk!!.close()
        registry.releaseBrowser(old.id); assertNull(old.pullDocument(8192).chunk)
        assertTrue(registry.claimBrowser(old.id)); assertFalse(old.requestDocumentAfterUserOpen())
        assertFalse(old.submitOwn("{\"v\":1}"))
        val replacement = register(registry, t = "c".repeat(32))
        assertTrue(registry.claimBrowser(replacement.id)); assertTrue(replacement.requestDocumentAfterUserOpen())
        registry.releaseBrowser(old.id); assertTrue(registry.browserOwnedBy(replacement.id))
        assertNull(old.pullDocument(8192).chunk); assertFalse(old.submitOwn("{\"v\":1}"))
    }

    @Test fun deadUnclaimedAndInvalidOffsetCannotAdmitReplyOrAllocateDelivery() {
        var alive = true; val registry = OpenFluxVerificationRegistry(); val request = register(registry, alive = { alive })
        assertFalse(request.pullDocument(0).waiting); assertNull(request.pullDocument(0).chunk)
        assertTrue(registry.claimBrowser(request.id)); assertTrue(request.requestDocumentAfterUserOpen())
        for (offset in listOf(-1, 1, 512 * 1024, Int.MAX_VALUE)) {
            assertFalse(request.pullDocument(offset).waiting); assertNull(request.pullDocument(offset).chunk)
        }
        stream(request, "synthetic"); alive = false
        assertNull(request.pullDocument(0).chunk); assertFalse(request.submitOwn("{\"v\":1}"))
        assertNull(OpenFluxOwnCommand.read("bad")); assertNull(OpenFluxOwnCommand.reply(ticket, "{}"))
        assertNull(OpenFluxOwnCommand.reply(ticket.uppercase(), "{\"v\":1}"))
    }
}
