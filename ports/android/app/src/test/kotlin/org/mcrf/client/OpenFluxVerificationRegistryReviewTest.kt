package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class OpenFluxVerificationRegistryReviewTest {
    private val child = "a".repeat(32)
    private val ticket = "b".repeat(32)
    private fun page() = OpenFluxVerificationPolicy.externalPage("https://captcha.example/check?private=synthetic", "", false, "")!!

    @Test fun observingBrowserOwnerDoesNotClaimOrOpenTheRequest() {
        val registry = OpenFluxVerificationRegistry()
        val request = registry.register(child, ticket, page(), { true }, { true })!!
        assertFalse(registry.browserOwnedBy(request.id)); assertFalse(request.opened.get())
        assertTrue(registry.claimBrowser(request.id)); assertTrue(registry.browserOwnedBy(request.id))
        registry.releaseBrowser(request.id); assertFalse(registry.browserOwnedBy(request.id))
        assertSame(request, registry.get(request.id))
    }

    @Test fun retiredOrExpiredBrowserIsNotReportedAsCurrentOwner() {
        var now = 0L
        val registry = OpenFluxVerificationRegistry { now }
        val old = registry.register(child, ticket, page(), { true }, { true })!!
        assertTrue(registry.claimBrowser(old.id))
        val current = registry.register(child, "c".repeat(32), page(), { true }, { true })!!
        assertFalse(registry.browserOwnedBy(old.id)); assertTrue(registry.claimBrowser(current.id))
        now = 150_000_000_000L
        assertFalse(registry.browserOwnedBy(current.id))
    }

    @Test fun duplicateTicketDoesNotRenewDeadline() {
        var now = 0L
        val registry = OpenFluxVerificationRegistry { now }
        val first = registry.register(child, ticket, page(), { true }, { true })!!
        now = 10_000_000_000L
        val duplicate = registry.register(child, ticket, page(), { true }, { true })!!
        assertSame(first, duplicate); assertEquals(140000L, duplicate.remainingMillis())
    }

    @Test fun queuedRequestCanClaimAfterPausedOwnerExpiresWithoutAnotherRegistration() {
        var now = 0L
        val registry = OpenFluxVerificationRegistry { now }
        val old = registry.register(child, ticket, page(), { true }, { true })!!
        assertTrue(registry.claimBrowser(old.id))
        now = 10_000_000_000L
        val waiting = registry.register("c".repeat(32), "d".repeat(32), page(), { true }, { true })!!
        assertFalse(registry.claimBrowser(waiting.id))
        now = 151_000_000_000L
        assertTrue(registry.claimBrowser(waiting.id)); assertTrue(registry.browserOwnedBy(waiting.id))
        assertNull(registry.get(old.id)); assertEquals(9000L, waiting.remainingMillis())
    }

    @Test fun queuedRequestCanClaimAfterPreviousChildDiesWithoutDeadlineRenewal() {
        var alive = true
        val registry = OpenFluxVerificationRegistry()
        val old = registry.register(child, ticket, page(), { alive }, { true })!!
        assertTrue(registry.claimBrowser(old.id))
        val waiting = registry.register("c".repeat(32), "d".repeat(32), page(), { true }, { true })!!
        assertFalse(registry.claimBrowser(waiting.id))
        alive = false
        assertTrue(registry.claimBrowser(waiting.id))
        assertNull(registry.get(old.id)); assertSame(waiting, registry.get(waiting.id))
    }

    @Test fun replacementRetiresOldReplyWithoutRedirectingIt() {
        var oldCalls = 0; var newCalls = 0
        val registry = OpenFluxVerificationRegistry()
        val old = registry.register(child, ticket, page(), { true }, { oldCalls++; true })!!
        val current = registry.register(child, "c".repeat(32), page(), { true }, { newCalls++; true })!!
        assertNull(registry.get(old.id)); assertFalse(old.submit("cookie=synthetic"))
        assertTrue(current.submit("cookie=synthetic"))
        assertEquals(0, oldCalls); assertEquals(1, newCalls)
    }

    @Test fun nativeAckIsScopedToExactChildAndTicket() {
        val registry = OpenFluxVerificationRegistry()
        val request = registry.register(child, ticket, page(), { true }, { true })!!
        assertNull(registry.complete("d".repeat(32), ticket))
        assertNull(registry.complete(child, "e".repeat(32)))
        assertSame(request, registry.get(request.id))
        assertEquals(request.id, registry.complete(child, ticket)); assertNull(registry.get(request.id))
    }

    @Test fun closeAndExpiryPreventLateSubmission() {
        var now = 0L; var calls = 0
        val registry = OpenFluxVerificationRegistry { now }
        val first = registry.register(child, ticket, page(), { true }, { calls++; true })!!
        now = 150_000_000_001L
        assertNull(registry.get(first.id)); assertFalse(first.submit("cookie=synthetic"))
        val second = registry.register(child, "c".repeat(32), page(), { true }, { calls++; true })!!
        assertEquals(listOf(second.id), registry.closeChild(child)); assertFalse(second.submit("cookie=synthetic"))
        assertEquals(0, calls)
    }

    @Test fun browserClaimIsExclusiveAndRetiredOwnerCannotCancelReplacement() {
        val registry = OpenFluxVerificationRegistry()
        val old = registry.register(child, ticket, page(), { true }, { true })!!
        assertTrue(registry.claimBrowser(old.id)); assertFalse(registry.claimBrowser(old.id))
        val current = registry.register(child, "c".repeat(32), page(), { true }, { true })!!
        registry.cancel(old.id)
        assertTrue(registry.claimBrowser(current.id)); assertSame(current, registry.get(current.id))
    }

    @Test fun rejectedQueueRetainsRequestAndAcceptedQueueIsOneShotNotAck() {
        var accept = false
        val registry = OpenFluxVerificationRegistry()
        val request = registry.register(child, ticket, page(), { true }, { accept })!!
        assertFalse(request.submit("cookie=synthetic")); assertFalse(request.submitted.get())
        accept = true
        assertTrue(request.submit("cookie=synthetic")); assertFalse(request.submit("cookie=synthetic"))
        assertSame(request, registry.get(request.id)) // Wait for explicit ACK/death/expiry.
        assertFalse(request.toString().contains("synthetic"))
    }

    @Test fun requestCapacityIsBoundedAndDeadChildIsNotPresented() {
        val registry = OpenFluxVerificationRegistry()
        repeat(32) { i -> assertNotNull(registry.register(i.toString(16).padStart(32, '0'), ticket, page(), { true }, { true })) }
        assertNull(registry.register("f".repeat(32), ticket, page(), { true }, { true }))
        var alive = true
        val isolated = OpenFluxVerificationRegistry()
        val request = isolated.register(child, ticket, page(), { alive }, { true })!!
        alive = false
        assertNull(isolated.unopened()); assertNull(isolated.get(request.id))
    }

    @Test fun recreateReopensSameUnsubmittedRequestWithoutRenewal() {
        var now = 0L
        val registry = OpenFluxVerificationRegistry { now }
        val request = registry.register(child, ticket, page(), { true }, { true })!!
        assertTrue(registry.claimBrowser(request.id))
        assertFalse(registry.reopenAfterRecreate(request.id)) // Release the existing browser first.
        registry.releaseBrowser(request.id); now = 20_000_000_000L
        assertTrue(registry.reopenAfterRecreate(request.id)); assertSame(request, registry.unopened())
        assertEquals(130000L, request.remainingMillis())
    }

    @Test fun recreateOfRetiredUiCannotReopenReplacement() {
        val registry = OpenFluxVerificationRegistry()
        val old = registry.register(child, ticket, page(), { true }, { true })!!
        val current = registry.register(child, "c".repeat(32), page(), { true }, { true })!!
        assertTrue(registry.claimBrowser(current.id))
        assertFalse(registry.reopenAfterRecreate(old.id)); assertTrue(current.opened.get())
    }

    @Test fun submittedReplyIsNotReofferedAfterRecreate() {
        val registry = OpenFluxVerificationRegistry()
        val request = registry.register(child, ticket, page(), { true }, { true })!!
        assertTrue(registry.claimBrowser(request.id)); assertTrue(request.submit("cookie=synthetic"))
        registry.releaseBrowser(request.id)
        assertFalse(registry.reopenAfterRecreate(request.id)); assertNull(registry.unopened())
        assertSame(request, registry.get(request.id))
    }

    @Test fun unclaimedRequestCanBeReofferedWhileAnotherBrowserOwnsTheSlot() {
        val registry = OpenFluxVerificationRegistry()
        val waiting = registry.register(child, ticket, page(), { true }, { true })!!
        waiting.opened.set(true)
        val active = registry.register("c".repeat(32), "d".repeat(32), page(), { true }, { true })!!
        assertTrue(registry.claimBrowser(active.id))
        assertTrue(registry.reopenAfterRecreate(waiting.id)); assertSame(waiting, registry.unopened())
        assertFalse(registry.claimBrowser(waiting.id))
        registry.releaseBrowser(active.id); assertTrue(registry.claimBrowser(waiting.id))
    }
}
