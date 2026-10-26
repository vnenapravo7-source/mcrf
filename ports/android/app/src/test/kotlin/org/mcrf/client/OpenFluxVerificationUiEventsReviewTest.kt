package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class OpenFluxVerificationUiEventsReviewTest {
    private val child = "a".repeat(32)
    private val ticket = "b".repeat(32)
    private fun page() = OpenFluxVerificationPolicy.externalPage("https://captcha.example/check?synthetic=private", "", false, "")!!
    private fun reoffer(registry: OpenFluxVerificationRegistry, id: String) =
        OpenFluxVerificationUiEvents.apply(registry, id, OpenFluxVerificationUiEvents.REOFFER)

    @Test fun failedOpeningReoffersExactRequestWithoutDeadlineRenewalOrNativeCall() {
        var now = 0L; var calls = 0
        val registry = OpenFluxVerificationRegistry { now }
        val request = registry.register(child, ticket, page(), { true }, { calls++; true })!!
        request.opened.set(true); now = 20_000_000_000L
        assertEquals(OpenFluxVerificationUiEvents.Result.REOFFERED, reoffer(registry, request.id))
        assertSame(request, registry.unopened()); assertEquals(130000L, request.remainingMillis())
        assertEquals(0, calls)
    }
    @Test fun lateFailureCannotReopenClaimedOrSubmittedWindow() {
        val registry = OpenFluxVerificationRegistry()
        val request = registry.register(child, ticket, page(), { true }, { true })!!
        assertTrue(registry.claimBrowser(request.id))
        assertEquals(OpenFluxVerificationUiEvents.Result.IGNORED, reoffer(registry, request.id))
        assertTrue(request.submit("cookie=synthetic")); registry.releaseBrowser(request.id)
        assertEquals(OpenFluxVerificationUiEvents.Result.IGNORED, reoffer(registry, request.id))
        assertNull(registry.unopened()); assertSame(request, registry.get(request.id))
    }
    @Test fun unknownActionsAndMalformedIdsDoNotCancelRequest() {
        var calls = 0; val registry = OpenFluxVerificationRegistry()
        val request = registry.register(child, ticket, page(), { true }, { calls++; true })!!
        assertEquals(OpenFluxVerificationUiEvents.Result.IGNORED,
            OpenFluxVerificationUiEvents.apply(registry, request.id, "synthetic-unknown"))
        assertEquals(OpenFluxVerificationUiEvents.Result.IGNORED, reoffer(registry, "malformed"))
        assertSame(request, registry.get(request.id)); assertEquals(0, calls)
    }
    @Test fun currentAndLegacyCancelRetireOnceAndOnlyExactTicket() {
        for (action in listOf(null, OpenFluxVerificationUiEvents.CANCEL)) {
            val commands = mutableListOf<String>(); val registry = OpenFluxVerificationRegistry()
            val request = registry.register(child, ticket, page(), { true }, { commands += it.toString(Charsets.US_ASCII); true })!!
            repeat(2) {
                assertEquals(OpenFluxVerificationUiEvents.Result.CANCELLED,
                    OpenFluxVerificationUiEvents.apply(registry, request.id, action))
            }
            assertEquals(listOf("OPENFLUX_CAPTCHA_CANCEL|$ticket\n"), commands)
            assertNull(registry.get(request.id))
        }
    }
    @Test fun staleOpeningFailureDoesNotReopenOrCancelReplacement() {
        val registry = OpenFluxVerificationRegistry()
        val old = registry.register(child, ticket, page(), { true }, { true })!!
        val current = registry.register(child, "c".repeat(32), page(), { true }, { true })!!
        assertTrue(registry.claimBrowser(current.id))
        assertEquals(OpenFluxVerificationUiEvents.Result.IGNORED, reoffer(registry, old.id))
        assertSame(current, registry.get(current.id)); assertTrue(current.opened.get())
    }
}
