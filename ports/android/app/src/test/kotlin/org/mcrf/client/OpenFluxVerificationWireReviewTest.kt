package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenFluxVerificationWireReviewTest {
    private val id = "a".repeat(32)
    private fun pending() = JSONObject().put("id", id).put("url", "https://captcha.example/check?secret=synthetic").put("own", false)
    private fun bytes(data: JSONObject) = (OpenFluxVerificationWire.PENDING + data).toByteArray(Charsets.UTF_8)

    @Test fun pendingIsTypedAndPrintableWithoutPrivateUrl() {
        val parsed = OpenFluxVerificationWire.pending(bytes(pending()))!!
        assertEquals(id, parsed.nativeId)
        assertEquals("https://captcha.example", parsed.page.origin)
        assertFalse(parsed.toString().contains("synthetic"))
    }

    @Test fun ownProxyUnknownFieldsAndTypeCoercionsAreRejected() {
        for (data in listOf(pending().put("own", true), pending().put("own", "false"), pending().put("proxy", "127.0.0.1:12345"), pending().put("proxy", 1), pending().put("html", "<html>private</html>"), pending().put("id", 123)))
            assertNull(OpenFluxVerificationWire.pending(bytes(data)))
    }

    @Test fun unsafeUrlTrailingDataAndMalformedUtf8AreRejected() {
        assertNull(OpenFluxVerificationWire.pending(bytes(pending().put("url", "http://127.0.0.1/"))))
        assertNull(OpenFluxVerificationWire.pending(bytes(pending()) + " extra".toByteArray()))
        assertNull(OpenFluxVerificationWire.pending(bytes(pending()) + "\u0000 trailing".toByteArray()))
        assertNull(OpenFluxVerificationWire.pending(bytes(pending()) + byteArrayOf(0xc3.toByte(), 0x28)))
    }

    @Test fun replyRequiresExactFieldsTicketAndFiniteStatus() {
        fun reply(data: JSONObject) = OpenFluxVerificationWire.reply((OpenFluxVerificationWire.REPLY + data).toByteArray())
        fun data() = JSONObject().put("id", id).put("status", "accepted")
        assertEquals(id, reply(data())!!.nativeId)
        assertNull(reply(data().put("status", "accepted synthetic-secret")))
        assertNull(reply(data().put("url", "https://private.example/")))
        assertNull(reply(data().put("id", id.uppercase())))
        assertNull(reply(data().put("status", true)))
    }

    @Test fun hostCapabilityNeedsExactFreshSignedSourceAndManualNonDiagnosticMode() {
        fun allowed(version: String? = OpenFluxProfile.FRESH_VERSION, commit: String? = OpenFluxProfile.FRESH_COMMIT,
                    contract: Int = 1, background: Boolean = false, diagnostic: Boolean = false) =
            OpenFluxProfile.supportsVerification(version, commit, contract, background, diagnostic)
        assertTrue(allowed())
        assertFalse(allowed(version = "v0.7.3")); assertFalse(allowed(commit = "0".repeat(40)))
        assertFalse(allowed(contract = 0)); assertFalse(allowed(contract = 2))
        assertFalse(allowed(background = true)); assertFalse(allowed(diagnostic = true))
    }

    @Test fun unknownPrefixesAndOversizeRecordsDoNotEnterProtocol() {
        assertFalse(OpenFluxVerificationWire.recognized("ordinary output"))
        assertNull(OpenFluxVerificationWire.pending(("OTHER " + pending()).toByteArray()))
        assertNull(OpenFluxVerificationWire.pending(ByteArray(16417) { 'x'.code.toByte() }))
        assertTrue(OpenFluxVerificationWire.recognized(OpenFluxVerificationWire.PENDING + "{}"))
    }

    @Test fun diagnosticFallbackCannotRetainPendingUrlOrReplyContents() {
        val detail = CarrierFailureDetail("openflux", JSONObject())
        detail.offer(OpenFluxVerificationWire.PENDING + pending())
        detail.offer(OpenFluxVerificationWire.REPLY + "{\"id\":\"$id\",\"status\":\"reply_failed\"}")
        val description = detail.describe("выход", 1)
        assertFalse(description.contains("synthetic")); assertFalse(description.contains("captcha.example"))
        assertFalse(description.contains(id))
    }
}
