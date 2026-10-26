package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Pure URL/HTML admission only. NOT real WebView, CSP enforcement or network proof. */
class OpenFluxHttpPagePolicyReviewTest {
    private val nonce = "a".repeat(32)
    private val native = "http://127.0.0.1:4321/check?x=%D1%8F"
    private val policy get() = OpenFluxHttpPagePolicy(native, nonce)
    private val html = listOf("Content-Type" to "text/html; charset=utf-8")
    @Test fun exactSyntheticOriginMapsRawPathAndQueryToCapturedNativeOrigin() {
        val p = policy
        assertEquals("https://$nonce.openflux.invalid/check?x=%D1%8F", p.initialUrl)
        assertEquals(native, p.interceptTarget(p.initialUrl, "GET"))
        assertEquals("http://127.0.0.1:4321/a%2Fb?k=%26", p.interceptTarget(p.origin + "/a%2Fb?k=%26", "HEAD"))
    }
    @Test fun directLoopbackExternalAndAlternateOriginsNeverFallBackToNetwork() {
        val p = policy
        for (url in listOf(native, "http://localhost:4321/check", "https://example.com/check", p.origin + ":443/check",
            p.origin.replace("https:", "http:") + "/check", "https://b${nonce}.openflux.invalid/check",
            "https://$nonce.openflux.invalid.evil/check", "file:///x", "content://x", "data:text/html,x"))
            assertNull(p.interceptTarget(url, "GET"))
    }
    @Test fun noFragmentUserInfoBackslashControlOrUnavailablePostBody() {
        val p = policy
        for (url in listOf(p.initialUrl + "#x", "https://u@$nonce.openflux.invalid/check", p.origin + "/\\x", p.origin + "/x\ny"))
            assertNull(p.interceptTarget(url, "GET"))
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS", "get")) assertNull(p.interceptTarget(p.initialUrl, method))
        assertFalse(p.initialNavigation(p.origin + "/other", "GET")); assertFalse(p.initialNavigation(p.initialUrl, "HEAD"))
        assertTrue(p.initialNavigation(p.initialUrl, "GET"))
    }
    @Test fun strictUtf8HtmlOnlyNoMimeSniffingOrLossyDecode() {
        assertEquals("<p>я</p>", policy.mainHtml(200, html, "<p>я</p>".toByteArray()))
        assertNull(policy.mainHtml(200, emptyList(), byteArrayOf(60, 112, 62)))
        assertNull(policy.mainHtml(200, listOf("Content-Type" to "application/javascript"), byteArrayOf(1)))
        assertNull(policy.mainHtml(200, html, byteArrayOf(0xc3.toByte(), 0x28)))
        assertNull(policy.mainHtml(200, listOf("Content-Type" to "text/html; charset=windows-1251"), byteArrayOf(1)))
    }
    @Test fun redirectsNoBodyStatusesCompressionAndAmbiguousHeadersRejected() {
        for (status in listOf(204, 301, 302, 304, 400, 500)) assertNull(policy.mainHtml(status, html, byteArrayOf()))
        assertNull(policy.mainHtml(200, html + ("Content-Encoding" to "gzip"), byteArrayOf(1)))
        assertNull(policy.mainHtml(200, html + ("content-type" to "text/html"), byteArrayOf(1)))
        assertNull(policy.mainHtml(200, html + listOf("Content-Encoding" to "identity", "content-encoding" to "identity"), byteArrayOf(1)))
        assertNotNull(policy.mainHtml(200, html + ("Content-Encoding" to "identity"), byteArrayOf()))
    }
    @Test fun boundsCanonicalSourceAndFreshOriginRequired() {
        assertNull(policy.mainHtml(200, html, ByteArray(524289)))
        assertNull(policy.interceptTarget(policy.origin + "/" + "x".repeat(8192), "GET"))
        for (address in listOf("http://localhost:4321/", "http://127.0.0.1/", "https://127.0.0.1:4321/")) {
            try { OpenFluxHttpPagePolicy(address, nonce); fail("invalid source admitted") } catch (_: IllegalArgumentException) { }
        }
        try { OpenFluxHttpPagePolicy(native, "not-a-nonce"); fail("invalid nonce admitted") } catch (_: IllegalArgumentException) { }
        assertEquals("https://$nonce.openflux.invalid/", OpenFluxHttpPagePolicy("http://127.0.0.1:4321", nonce).initialUrl)
    }
    @Test fun policyIsRedactedAndDoesNotClaimExternalNetworkOrRendererAdmission() {
        assertEquals("OpenFluxHttpPagePolicy(redacted)", policy.toString())
        for (directive in listOf("connect-src 'none'", "frame-src 'none'", "worker-src 'none'", "form-action 'none'", "base-uri 'none'"))
            assertTrue(OpenFluxHttpPagePolicy.CSP.contains(directive))
    }
}
