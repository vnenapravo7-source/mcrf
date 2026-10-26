package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class OpenFluxVerificationReviewTest {
    private val child = "a".repeat(32)
    private val request = "b".repeat(32)

    @Test fun onlyExplicitlyApprovedExactOriginCanNavigate() {
        val page = OpenFluxVerificationPolicy.externalPage("https://captcha.example/check?token=synthetic", "", false, "")!!
        assertFalse(OpenFluxVerificationPolicy.mayNavigate(page, page.url, null))
        assertTrue(OpenFluxVerificationPolicy.mayNavigate(page, "https://captcha.example:443/next", page.origin))
        for (target in listOf("https://sub.captcha.example/", "https://captcha.example.attacker.invalid/", "http://captcha.example/"))
            assertFalse(OpenFluxVerificationPolicy.mayNavigate(page, target, page.origin))
        assertFalse(page.toString().contains("synthetic"))
    }

    @Test fun ownInlineAndExitProxyPagesAreNotExternalPages() {
        val url = "https://captcha.example/check"
        assertNull(OpenFluxVerificationPolicy.externalPage(url, "<html>private</html>", false, ""))
        assertNull(OpenFluxVerificationPolicy.externalPage(url, "", true, ""))
        assertNull(OpenFluxVerificationPolicy.externalPage(url, "", false, "127.0.0.1:12345"))
    }

    @Test fun unsafeAddressesAndVkOriginsStayOutsideThisContract() {
        for (url in listOf("file:///data/secret", "https://127.0.0.1/", "https://[::1]/", "https://x.local/", "https://localhost/", "https://user:secret@captcha.example/", "https://captcha.example:8443/", "https://captcha.example/#private", "https://captcha.example/\n", "https://vk.com/", "https://id.vk.ru/"))
            assertNull(url, OpenFluxVerificationPolicy.externalPage(url, "", false, ""))
    }

    @Test fun replyIsBoundToExactChildAndRequest() {
        var calls = 0
        val reply = OpenFluxVerificationReply(child, request, { true }, { calls++; true })
        assertFalse(reply.submit("c".repeat(32), request, "a=synthetic"))
        assertFalse(reply.submit(child, "d".repeat(32), "a=synthetic"))
        assertEquals(0, calls)
        assertTrue(reply.submit(child, request, "a=synthetic"))
        assertFalse(reply.submit(child, request, "a=synthetic"))
        assertEquals(1, calls)
    }

    @Test fun queueRejectionRetainsSlotAndTemporaryCommandIsWiped() {
        var ready = false
        var observed: ByteArray? = null
        val reply = OpenFluxVerificationReply(child, request, { true }, { observed = it; ready })
        assertFalse(reply.submit(child, request, "cookie=synthetic|value"))
        assertTrue(observed!!.all { it == 0.toByte() })
        ready = true
        assertTrue(reply.submit(child, request, "cookie=synthetic|value"))
        assertTrue(observed!!.all { it == 0.toByte() })
    }

    @Test fun expiredAndClosedSlotsNeverDeliver() {
        var alive = false
        var calls = 0
        val reply = OpenFluxVerificationReply(child, request, { alive }, { calls++; true })
        assertFalse(reply.submit(child, request, "a=synthetic"))
        alive = true
        assertFalse(reply.submit(child, request, "a=synthetic")) // A retired slot cannot resurrect.
        reply.close()
        assertFalse(reply.submit(child, request, "a=synthetic"))
        assertEquals(0, calls)
    }

    @Test fun invalidCookieHeadersAreRejectedWithoutDelivery() {
        var calls = 0
        val reply = OpenFluxVerificationReply(child, request, { true }, { calls++; true })
        for (value in listOf("", "a=1\r\nInjected: bad", "a=😀", "a".repeat(8193)))
            assertFalse(reply.submit(child, request, value))
        assertEquals(0, calls)
        assertFalse(reply.toString().contains(request))
    }
}
