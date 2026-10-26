package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Shared-pool/session adapters only, not real Binder/child/window evidence. */
class OpenFluxHttpRegistryReviewTest {
    private val child = "a".repeat(32); private val ticket = "b".repeat(32); private val permit = "d".repeat(32)
    private fun own(registry: OpenFluxVerificationRegistry, offer: (ByteArray) -> Boolean = { true }) =
        registry.registerHttp(child, OpenFluxHttpWire.Pending(ticket), { true }, offer)!!
    private fun page() = OpenFluxVerificationPolicy.externalPage("https://captcha.example/check", "", false, "")!!
    private fun opened(request: OpenFluxVerificationRegistry.Request, opening: String) = frame(OpenFluxHttpWire.OPENED,
        JSONObject().put("id", request.nativeId).put("request", opening.trim().substringAfterLast('|'))
            .put("permit", permit).put("address", "http://127.0.0.1:4321/path"))
    private fun bind(registry: OpenFluxVerificationRegistry, request: OpenFluxVerificationRegistry.Request): String {
        assertTrue(registry.claimBrowser(request.id))
        val window = OpenFluxWindowAdmission(); window.resume()
        return request.openHttpAfterUserAction(window, window.capture())!!.toString(Charsets.US_ASCII)
    }
    @Test fun httpDiscoveryRequiresExplicitOptInAndDoesNotClaimOrSendAnything() {
        val registry = OpenFluxVerificationRegistry(); var writes = 0
        val request = own(registry) { writes++; true }
        assertNull(registry.unopened()); assertNull(registry.unopened(includeInline = true))
        assertSame(request, registry.unopened(includeHttp = true))
        assertSame(request, registry.unopened(includeInline = true, includeHttp = true))
        assertFalse(registry.browserOwnedBy(request.id)); assertFalse(request.opened.get()); assertEquals(0, writes)
        request.opened.set(true); assertNull(registry.unopened(includeHttp = true))
    }
    @Test fun discoveredHttpCancelStillTargetsOnlyItsCapturedNativeTicket() {
        val registry = OpenFluxVerificationRegistry(); val writes = mutableListOf<String>()
        val request = own(registry) { writes.add(it.toString(Charsets.US_ASCII)); true }
        assertSame(request, registry.unopened(includeHttp = true))
        assertEquals(OpenFluxVerificationUiEvents.Result.IGNORED,
            OpenFluxVerificationUiEvents.apply(registry, request.id, OpenFluxVerificationUiEvents.REOFFER))
        assertTrue(writes.isEmpty())
        assertEquals(OpenFluxVerificationUiEvents.Result.CANCELLED,
            OpenFluxVerificationUiEvents.apply(registry, request.id, OpenFluxVerificationUiEvents.CANCEL))
        assertEquals(listOf("OPENFLUX_CAPTCHA_CANCEL|$ticket\n"), writes)
        assertNull(registry.get(request.id)); assertNull(registry.unopened(includeHttp = true))
    }
    @Test fun httpKindIsNeitherCookieNorOfflineDocumentAndCannotExposeExternalPage() {
        val registry = OpenFluxVerificationRegistry(); val request = own(registry)
        assertTrue(request.http); assertFalse(request.inline); assertEquals(OpenFluxVerificationRegistry.Kind.HTTP, request.kind)
        try { request.page; fail("HTTP is not an external URL") } catch (_: IllegalStateException) { }
        assertFalse(request.submit("sample=private")); assertFalse(request.submitOwn("{\"sample\":true}"))
        assertFalse(request.requestDocumentAfterUserOpen())
    }
    @Test fun duplicateTicketCannotChangeKindAndNeverRenewsOpaqueIdentity() {
        val registry = OpenFluxVerificationRegistry(); val request = own(registry)
        assertSame(request, own(registry))
        assertNull(registry.registerInline(child, OpenFluxDocumentWire.Pending(ticket), { true }, { true }))
        assertNull(registry.register(child, ticket, page(), { true }, { true }))
        assertSame(request, registry.get(request.id))
    }
    @Test fun allThreeKindsShareThirtyTwoSlotsAndOneBrowserOwner() {
        val registry = OpenFluxVerificationRegistry(); val items = (1..32).map { index ->
            val c = "%032x".format(index)
            when (index % 3) {
                0 -> registry.registerHttp(c, OpenFluxHttpWire.Pending(ticket), { true }, { true })!!
                1 -> registry.registerInline(c, OpenFluxDocumentWire.Pending(ticket), { true }, { true })!!
                else -> registry.register(c, ticket, page(), { true }, { true })!!
            }
        }
        assertNull(registry.registerHttp("f".repeat(32), OpenFluxHttpWire.Pending(ticket), { true }, { true }))
        assertTrue(registry.claimBrowser(items[0].id)); assertFalse(registry.claimBrowser(items[1].id))
    }
    @Test fun httpIsExcludedFromBothOldUiSelectorsAndRecreation() {
        val registry = OpenFluxVerificationRegistry(); val request = own(registry)
        assertNull(registry.unopened()); assertNull(registry.unopened(includeInline = true))
        assertFalse(registry.reopenAfterRecreate(request.id))
        assertEquals(OpenFluxVerificationUiEvents.Result.IGNORED,
            OpenFluxVerificationUiEvents.apply(registry, request.id, OpenFluxVerificationUiEvents.REOFFER))
    }
    @Test fun exactBrowserClaimAndForegroundOpenAreRequiredWithoutImplicitWrite() {
        val registry = OpenFluxVerificationRegistry(); var writes = 0
        val request = own(registry) { writes++; true }; val window = OpenFluxWindowAdmission(); window.resume()
        assertNull(request.openHttpAfterUserAction(window, window.capture()))
        assertTrue(registry.claimBrowser(request.id))
        assertNull(request.openHttpAfterUserAction(window, null))
        val command = request.openHttpAfterUserAction(window, window.capture())!!.toString(Charsets.US_ASCII)
        assertTrue(command.startsWith("OPENFLUX_HTTP_OPEN|$ticket|")); assertTrue(command.endsWith("\n"))
        assertEquals(0, writes); assertNull(request.openHttpAfterUserAction(window, window.capture()))
    }
    @Test fun scopedResponseAdapterTransfersOnlyCompleteBinaryResponse() {
        val registry = OpenFluxVerificationRegistry(); val request = own(registry); val opening = bind(registry, request)
        assertEquals(OpenFluxHttpSession.Received.CONSUMED, request.receiveHttp(child, opened(request, opening)))
        val id = request.enqueueHttp("GET", "http://127.0.0.1:4321/path", emptyList(), byteArrayOf())!!
        assertNotNull(request.nextHttpCommand()); assertNull(request.nextHttpCommand()); assertNull(request.takeHttpResponse())
        request.receiveHttp(child, frame(OpenFluxHttpWire.META, JSONObject().put("id", ticket).put("request", id)
            .put("status", 200).put("headers", JSONArray()).put("bytes", 0)
            .put("sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")))
        assertEquals(OpenFluxHttpSession.Received.COMPLETE, request.receiveHttp(child,
            frame(OpenFluxHttpWire.END, JSONObject().put("id", ticket).put("request", id))))
        val response = request.takeHttpResponse()!!; assertEquals(0, response.body.size); response.close()
    }
    @Test fun releaseBeforeOpenedRetiresTicketAndLatePermitCannotReopen() {
        val registry = OpenFluxVerificationRegistry(); val writes = mutableListOf<String>()
        val request = own(registry) { writes.add(it.toString(Charsets.US_ASCII)); true }; val opening = bind(registry, request)
        registry.releaseBrowser(request.id); assertNull(registry.get(request.id))
        assertEquals(listOf("OPENFLUX_CAPTCHA_CANCEL|$ticket\n"), writes)
        request.receiveHttp(child, opened(request, opening))
        assertEquals("OPENFLUX_HTTP_CLOSE|$ticket|$permit\n", request.takeHttpCancelCommand()!!.toString(Charsets.US_ASCII))
        assertNull(request.nextHttpCommand())
    }
    @Test fun releaseAfterOpenedStagesPermitCancelAndGenericCancelOnlyOnce() {
        val registry = OpenFluxVerificationRegistry(); var writes = 0
        val request = own(registry) { writes++; true }; val opening = bind(registry, request)
        request.receiveHttp(child, opened(request, opening)); registry.releaseBrowser(request.id); registry.releaseBrowser(request.id)
        assertEquals(1, writes); assertNotNull(request.takeHttpCancelCommand()); assertNull(request.takeHttpCancelCommand())
        assertFalse(registry.reopenAfterRecreate(request.id))
    }
    @Test fun cancellationRemovesExactOpaqueRequestBeforeOfferingNativeCancel() {
        val registry = OpenFluxVerificationRegistry(); var id: String? = null; var writes = 0
        val request = own(registry) { writes++; assertNull(registry.get(id!!)); true }; id = request.id
        assertTrue(registry.cancel(request.id)); assertTrue(registry.cancel(request.id)); assertEquals(1, writes)
    }
    @Test fun expiryClosesSessionAndDoesNotRenewNativeRequestOrWriteImplicitly() {
        var clock = 0L; val registry = OpenFluxVerificationRegistry { clock }; var writes = 0
        val request = own(registry) { writes++; true }; val opening = bind(registry, request)
        request.receiveHttp(child, opened(request, opening)); clock = 150_000_000_000L
        assertNull(registry.get(request.id)); assertNull(request.nextHttpCommand()); assertEquals(0, writes)
        assertNotNull(request.takeHttpCancelCommand()) // Caller still must deliver; not a runtime cancellation receipt.
    }
    companion object { private fun frame(prefix: String, data: JSONObject) = (prefix + data).toByteArray(Charsets.UTF_8) }
}
