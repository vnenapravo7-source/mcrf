package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** Prepared source models, not native stdout/Android window or provider receipts. */
class OpenFluxDocumentDispatchReviewTest {
    private val child = "a".repeat(32)
    private val ticket = "b".repeat(32)
    private val registry = OpenFluxVerificationRegistry()
    private var current: OpenFluxVerificationRegistry.Request? = null
    private var observed: String? = null
    private var offers = 0
    private fun frame(prefix: String, body: JSONObject) = (prefix + body).toByteArray(Charsets.UTF_8)
    private fun pending(t: String = ticket) = frame(OpenFluxDocumentWire.PENDING, JSONObject().put("id", t).put("kind", "inline"))
    private fun end(t: String = ticket) = frame(OpenFluxDocumentWire.END, JSONObject().put("id", t))
    private fun dispatch(bytes: ByteArray, enabled: Boolean = true, closed: Boolean = false, c: String = child,
                         alive: Boolean = true): OpenFluxDocumentDispatch.Result =
        OpenFluxDocumentDispatch.receive(bytes, c, enabled, closed, registry, current?.id, observed,
            { alive }, { offers++; true })
    private fun register(): OpenFluxVerificationRegistry.Request {
        val result = dispatch(pending())
        assertEquals(OpenFluxDocumentDispatch.State.PENDING, result.state)
        return result.request!!.also { current = it; observed = it.nativeId }
    }
    @Test fun disabledAndClosedConsumeReservedFamilyWithoutRegistrationOrOffers() {
        assertEquals(OpenFluxDocumentDispatch.State.REJECTED, dispatch(pending(), enabled = false).state)
        assertEquals(OpenFluxDocumentDispatch.State.CONSUMED, dispatch(pending(), closed = true).state)
        assertNull(registry.unopened(true)); assertEquals(0, offers)
    }
    @Test fun ordinaryDiagnosticsPassButMalformedPrivateFamilyNeverDoes() {
        assertEquals(OpenFluxDocumentDispatch.State.NOT_DOCUMENT, dispatch("ordinary".toByteArray()).state)
        val secret = "MCRF_OPENFLUX_DOC malformed private-html"
        val result = dispatch(secret.toByteArray())
        assertEquals(OpenFluxDocumentDispatch.State.REJECTED, result.state)
        assertFalse(result.toString().contains("private-html"))
    }
    @Test fun pendingDoesNotOpenReadAndExplicitEnumerationKeepsExternalDefault() {
        val request = register()
        assertEquals(0, offers); assertNull(registry.unopened()); assertSame(request, registry.unopened(true))
        assertFalse(request.requestDocumentAfterUserOpen()); assertFalse(request.submitted.get())
        assertSame(request, dispatch(pending()).request)
    }
    @Test fun streamOnlyCompletesAfterManualOpenAndFullDigest() {
        val request = register(); assertTrue(registry.claimBrowser(request.id))
        assertTrue(request.requestDocumentAfterUserOpen()); assertEquals(1, offers)
        val raw = "<p>synthetic ✓</p>".toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(OpenFluxDocumentDispatch.State.CONSUMED, dispatch(frame(OpenFluxDocumentWire.META,
            JSONObject().put("id", ticket).put("bytes", raw.size).put("sha256", sha))).state)
        assertNull(request.takeDocument(child, ticket))
        assertEquals(OpenFluxDocumentDispatch.State.CONSUMED, dispatch(frame(OpenFluxDocumentWire.CHUNK,
            JSONObject().put("id", ticket).put("offset", 0).put("data", Base64.getEncoder().encodeToString(raw)))).state)
        assertEquals(OpenFluxDocumentDispatch.State.CONSUMED, dispatch(end()).state)
        assertEquals(raw.toString(Charsets.UTF_8), request.takeDocument(child, ticket))
        assertNull(request.takeDocument(child, ticket))
    }
    @Test fun unsolicitedAndBeforeOpenFramesRejectWithoutNativeReply() {
        assertEquals(OpenFluxDocumentDispatch.State.REJECTED, dispatch(end()).state)
        register(); assertEquals(OpenFluxDocumentDispatch.State.REJECTED, dispatch(end()).state)
        assertEquals(0, offers)
    }
    @Test fun cancelledAndCompletedTicketNotResurrectedByDuplicatePending() {
        for (complete in listOf(false, true)) {
            current = null; observed = null
            val request = register()
            if (complete) registry.complete(child, ticket) else registry.cancel(request.id)
            assertEquals(OpenFluxDocumentDispatch.State.STALE, dispatch(end()).state)
            assertEquals(OpenFluxDocumentDispatch.State.STALE, dispatch(pending()).state)
            assertNull(registry.unopened(true))
        }
    }
    @Test fun staleFramesDoNotPoisonReplacementAndForeignChildCannotReceive() {
        register()
        val replacement = dispatch(pending("c".repeat(32))).request!!
        current = replacement; observed = replacement.nativeId
        assertEquals(OpenFluxDocumentDispatch.State.STALE, dispatch(end()).state)
        assertEquals(OpenFluxDocumentDispatch.State.REJECTED, dispatch(end(replacement.nativeId), c = "d".repeat(32)).state)
        assertSame(replacement, registry.get(replacement.id))
    }
    @Test fun sameTicketCannotSwitchExternalPageIntoInlineRenderer() {
        val page = OpenFluxVerificationPolicy.externalPage("https://captcha.example/check", "", false, "")!!
        current = registry.register(child, ticket, page, { true }, { true })!!; observed = ticket
        assertEquals(OpenFluxDocumentDispatch.State.REJECTED, dispatch(pending()).state)
        assertEquals(OpenFluxDocumentDispatch.State.REJECTED, dispatch(end()).state)
        assertFalse(registry.get(current!!.id)!!.inline)
    }
    @Test fun deadChildCannotRegisterAndProductionGateStaysClosed() {
        assertEquals(OpenFluxDocumentDispatch.State.STALE, dispatch(pending(), alive = false).state)
        assertFalse(OpenFluxOwnVerificationPolicy.productionEnabled)
        assertTrue(OpenFluxOwnVerificationPolicy.sourceCompatible(true, 1))
        assertFalse(OpenFluxOwnVerificationPolicy.sourceCompatible(false, 1))
        for (contract in listOf(-1, 0, 2)) assertFalse(OpenFluxOwnVerificationPolicy.sourceCompatible(true, contract))
    }
    @Test fun documentFailureUsesExactFiniteTokenNeverProviderPayload() {
        val token = "MCRF_OPENFLUX_FAILURE verification_document_failed"
        assertNotNull(OpenFluxFailure.describe("openflux", token))
        assertNull(OpenFluxFailure.describe("wdtt", token))
        assertNull(OpenFluxFailure.describe("openflux", "$token private-html"))
    }
}
