package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class OpenFluxJsonNestingReviewTest {
    @Test fun nestingIsBoundedBeforeRecursiveParsing() {
        assertTrue(OpenFluxJsonNesting.bounded("[".repeat(16) + "0" + "]".repeat(16)))
        assertFalse(OpenFluxJsonNesting.bounded("[".repeat(17) + "0" + "]".repeat(17)))
        assertFalse(OpenFluxJsonNesting.bounded("[".repeat(4000) + "0" + "]".repeat(4000)))
        for (text in listOf("{]", "[}", "]", "{", "{\"k\":[1}")) assertFalse(OpenFluxJsonNesting.bounded(text))
    }
    @Test fun stringsAndEscapesCannotHideStructuralDepth() {
        assertTrue(OpenFluxJsonNesting.bounded("{\"k\":\"[[{{\\\"\\\\\\n\\u0041}}]]\"}"))
        for (text in listOf("{\"k\":\"bad\\q\"}", "{\"k\":\"bad\\u00xz\"}", "{\"k\":\"bad\n\"}", "{\"k\":\"unfinished}"))
            assertFalse(OpenFluxJsonNesting.bounded(text))
    }
    @Test fun lenientCommentsSingleQuotesAndUnquotedNamesCannotDesyncDepthScanner() {
        for (text in listOf("{/*\"*/\"x\":[0]}", "{//comment\n\"x\":1}", "{#comment\n\"x\":1}",
            "{'x':'\"'}", "{unquoted:1}", "{\"x\":NaN}", "{\"x\":01}", "{\"x\":1e}", "{\"x\":.2}",
            "[true\",[[0]],\"]", "[1\",[[0]],\"]"))
            assertFalse(OpenFluxJsonNesting.bounded(text))
        assertTrue(OpenFluxJsonNesting.bounded("{\"x\":-1.25e+3,\"b\":true,\"n\":null}"))
    }
    @Test fun bothPrivateWireParsersRejectDeepUnexpectedValuesBeforeObjectConstruction() {
        val deep = "[".repeat(200) + "0" + "]".repeat(200)
        val json = "{\"id\":\"${"a".repeat(32)}\",\"extra\":$deep}"
        assertNull(OpenFluxDocumentWire.metadata((OpenFluxDocumentWire.META + json).toByteArray()))
        assertNull(OpenFluxDocumentWire.chunk((OpenFluxDocumentWire.CHUNK + json).toByteArray()))
        assertNull(OpenFluxVerificationWire.pending((OpenFluxVerificationWire.PENDING + json).toByteArray()))
        assertNull(OpenFluxVerificationWire.reply((OpenFluxVerificationWire.REPLY + json).toByteArray()))
    }
    @Test fun deeplyNestedPageMessageDoesNotOccupyManualReplySlot() {
        val session = OpenFluxOwnPageSession { true }
        val deep = "{\"k\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
        assertFalse(session.stage(deep)); assertTrue(session.stage("{\"answer\":true}"))
        assertTrue(session.submitAfterUserAction { it == "{\"answer\":true}" })
    }
}
