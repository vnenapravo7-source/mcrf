package org.mcrf.client

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Source-only synthetic ownership; not a Binder/UI-consent or native-pipe receipt. */
class OpenFluxInlineRegistryReviewTest {
    private val child = "a".repeat(32)
    private val ticket = "b".repeat(32)
    private fun page() = OpenFluxVerificationPolicy.externalPage("https://captcha.example/check", "", false, "")!!
    private fun own(registry: OpenFluxVerificationRegistry, c: String = child, t: String = ticket,
                    alive: () -> Boolean = { true }, offer: (ByteArray) -> Boolean = { true }) =
        registry.registerInline(c, OpenFluxDocumentWire.Pending(t), alive, offer)!!
    private fun frame(prefix: String, body: JSONObject) = (prefix + body).toByteArray(Charsets.UTF_8)
    private fun meta(raw: ByteArray, t: String = ticket): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) }
        return frame(OpenFluxDocumentWire.META, JSONObject().put("id", t).put("bytes", raw.size).put("sha256", digest))
    }
    private fun finish(request: OpenFluxVerificationRegistry.Request, text: String = "<p>synthetic</p>") {
        val raw = text.toByteArray(Charsets.UTF_8)
        assertEquals(OpenFluxDocumentReceiver.Result.CONSUMED, request.receiveDocument(child, meta(raw)))
        assertEquals(OpenFluxDocumentReceiver.Result.CONSUMED, request.receiveDocument(child,
            frame(OpenFluxDocumentWire.CHUNK, JSONObject().put("id", ticket).put("offset", 0)
                .put("data", Base64.getEncoder().encodeToString(raw)))))
        assertEquals(OpenFluxDocumentReceiver.Result.COMPLETE, request.receiveDocument(child,
            frame(OpenFluxDocumentWire.END, JSONObject().put("id", ticket))))
    }

    @Test fun inlineAndExternalShareOneBrowserButCookieUiNeverOffersInline() {
        val registry = OpenFluxVerificationRegistry()
        val inline = own(registry)
        val external = registry.register("c".repeat(32), "d".repeat(32), page(), { true }, { true })!!
        assertSame(external, registry.unopened())
        assertFalse(inline.requestDocumentAfterUserOpen())
        assertTrue(registry.claimBrowser(external.id)); assertFalse(registry.claimBrowser(inline.id))
        registry.releaseBrowser(external.id)
        assertTrue(registry.claimBrowser(inline.id)); assertTrue(registry.browserOwnedBy(inline.id))
        assertTrue(inline.requestDocumentAfterUserOpen()); assertFalse(inline.requestDocumentAfterUserOpen())
        assertFalse(inline.submit("cookie=synthetic"))
        finish(inline); assertEquals("<p>synthetic</p>", inline.takeDocument(child, ticket))
        assertNull(inline.takeDocument(child, ticket))
    }

    @Test fun ticketCannotChangeKindOrUrlAndDuplicateDoesNotRenewDeadline() {
        var now = 0L
        val registry = OpenFluxVerificationRegistry { now }
        val request = own(registry)
        now = 20_000_000_000L
        assertSame(request, own(registry)); assertEquals(130000L, request.remainingMillis())
        assertNull(registry.register(child, ticket, page(), { true }, { true }))
        assertSame(request, registry.get(request.id))
        val replacement = registry.register(child, "e".repeat(32), page(), { true }, { true })!!
        val changed = OpenFluxVerificationPolicy.externalPage("https://captcha.example/different", "", false, "")!!
        assertNull(registry.register(child, replacement.nativeId, changed, { true }, { true }))
        assertSame(replacement, registry.get(replacement.id))
    }

    @Test fun releaseDropsVerifiedHtmlAndSameTicketCannotRearmWithoutWireNonce() {
        val registry = OpenFluxVerificationRegistry(); val request = own(registry)
        assertTrue(registry.claimBrowser(request.id)); assertTrue(request.requestDocumentAfterUserOpen())
        finish(request)
        registry.releaseBrowser(request.id)
        assertNull(request.takeDocument(child, ticket))
        assertTrue(registry.reopenAfterRecreate(request.id)); assertTrue(registry.claimBrowser(request.id))
        assertFalse(request.requestDocumentAfterUserOpen())
        assertEquals(OpenFluxDocumentReceiver.Result.REJECTED, request.receiveDocument(child, meta("old".toByteArray())))
    }

    @Test fun replacementAndLateOldReleaseCannotDropAnotherWindowsDocument() {
        val registry = OpenFluxVerificationRegistry(); val old = own(registry)
        assertTrue(registry.claimBrowser(old.id)); assertTrue(old.requestDocumentAfterUserOpen()); finish(old)
        val replacement = own(registry, t = "c".repeat(32))
        assertNull(old.takeDocument(child, ticket)); assertFalse(registry.browserOwnedBy(old.id))
        assertTrue(registry.claimBrowser(replacement.id)); assertTrue(replacement.requestDocumentAfterUserOpen())
        registry.releaseBrowser(old.id); registry.cancel(old.id)
        assertTrue(registry.browserOwnedBy(replacement.id)); assertSame(replacement, registry.get(replacement.id))
    }

    @Test fun childDeathExpiryAckAndCloseInvalidateOwnedDocument() {
        for (mode in 0..3) {
            var alive = true; var now = 0L
            val registry = OpenFluxVerificationRegistry { now }; val request = own(registry, alive = { alive })
            assertTrue(registry.claimBrowser(request.id)); assertTrue(request.requestDocumentAfterUserOpen()); finish(request)
            when (mode) {
                0 -> alive = false
                1 -> now = 150_000_000_000L
                2 -> assertEquals(request.id, registry.complete(child, ticket))
                3 -> assertEquals(listOf(request.id), registry.closeChild(child))
            }
            assertNull(request.takeDocument(child, ticket)); assertFalse(request.requestDocumentAfterUserOpen())
            assertFalse(registry.browserOwnedBy(request.id))
        }
    }

    @Test fun mixedCapacityStaysBoundedAndCancellationRunsOfferOutsideRegistryLock() {
        val registry = OpenFluxVerificationRegistry()
        repeat(31) { i -> registry.register(i.toString(16).padStart(32, '0'), ticket, page(), { true }, { true })!! }
        var observed = false
        val request = own(registry, offer = { bytes ->
            // A different thread must be able to enter the registry during native offer.
            val probe = Thread { observed = registry.get("not-present") == null }
            probe.start(); probe.join(1000)
            assertFalse(probe.isAlive)
            assertTrue(bytes.toString(Charsets.US_ASCII).startsWith("OPENFLUX_CAPTCHA_CANCEL|")); true
        })
        assertNull(registry.registerInline("f".repeat(32), OpenFluxDocumentWire.Pending(ticket), { true }, { true }))
        assertTrue(registry.cancel(request.id)); assertTrue(observed)
        assertNotNull(registry.registerInline("f".repeat(32), OpenFluxDocumentWire.Pending(ticket), { true }, { true }))
    }

    @Test fun externalRequestCannotArmOrConsumeAnInlineDocument() {
        val registry = OpenFluxVerificationRegistry()
        val external = registry.register(child, ticket, page(), { true }, { true })!!
        assertTrue(registry.claimBrowser(external.id)); assertFalse(external.requestDocumentAfterUserOpen())
        assertEquals(OpenFluxDocumentReceiver.Result.REJECTED, external.receiveDocument(child, meta("x".toByteArray())))
        assertNull(external.takeDocument(child, ticket)); assertTrue(external.submit("cookie=synthetic"))
    }
}
