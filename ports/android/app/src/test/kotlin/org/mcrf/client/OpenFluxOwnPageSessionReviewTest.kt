package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Pure reply-slot shape/lifetime models. No actual WebView/port/manual gesture proof. */
class OpenFluxOwnPageSessionReviewTest {
    @Test fun stagingDoesNotSendAndExplicitSubmissionIsOneShot() {
        var calls = 0
        val session = OpenFluxOwnPageSession { true }
        assertTrue(session.stage("{\"answer\":\"synthetic\"}")); assertEquals(0, calls)
        assertFalse(session.stage("{\"answer\":\"replacement\"}"))
        assertTrue(session.submitAfterUserAction { calls++; it.contains("synthetic") })
        assertFalse(session.submitAfterUserAction { calls++; true }); assertEquals(1, calls)
        assertFalse(session.stage("{\"answer\":\"later\"}"))
        assertEquals("OpenFluxOwnPageSession(redacted)", session.toString())
    }
    @Test fun rejectedOrThrowingOfferRetainsOriginalReplyForManualRetry() {
        val session = OpenFluxOwnPageSession { true }; var received = ""
        val payload = "{\"answer\":\"synthetic\"}"
        assertTrue(session.stage(payload))
        assertFalse(session.submitAfterUserAction { false })
        assertFalse(session.submitAfterUserAction { throw IllegalStateException("synthetic") })
        assertTrue(session.submitAfterUserAction { received = it; true }); assertEquals(payload, received)
    }
    @Test fun lostOwnershipCloseOrThrowingPredicateCannotReviveReply() {
        for (mode in 0..2) {
            var valid = true; var throwing = false; var offers = 0
            val session = OpenFluxOwnPageSession { if (throwing) error("synthetic") else valid }
            assertTrue(session.stage("{\"answer\":true}"))
            when (mode) { 0 -> valid = false; 1 -> session.close(); 2 -> throwing = true }
            assertFalse(session.submitAfterUserAction { offers++; true })
            valid = true; throwing = false
            assertFalse(session.stage("{\"answer\":true}")); assertEquals(0, offers)
        }
    }
    @Test fun onlyObjectsWithDeliverableClientScalarsPassShapeGate() {
        val valid = listOf("{\"s\":\"x\"}", "{\"n\":2,\"b\":false}",
            "{\"client\":{\"s\":\"x\"},\"node\":{\"secret\":\"ignored\"}}",
            "{\"client\":\"flat-value\"}", "{\"node\":\"flat-value\"}")
        valid.forEach { assertTrue(OpenFluxOwnPageSession.admissible(it)) }
        val invalid = listOf("{}", "[]", "null", "1", "{\"\":\"ignored\"}", "{\"nested\":{\"a\":1}}", "{\"array\":[1]}",
            "{\"client\":{},\"flat\":\"ignored\"}", "{\"client\":{\"v\":null},\"node\":{\"v\":\"not-client\"}}")
        invalid.forEach { assertFalse(OpenFluxOwnPageSession.admissible(it)) }
    }
    @Test fun utf8ByteBudgetAndUnpairedSurrogatesFailClosed() {
        val prefix = "{\"v\":\""; val suffix = "\"}"
        val room = OpenFluxOwnPageSession.MAX_BYTES - prefix.length - suffix.length
        assertTrue(OpenFluxOwnPageSession.admissible(prefix + "a".repeat(room) + suffix))
        assertFalse(OpenFluxOwnPageSession.admissible(prefix + "a".repeat(room + 1) + suffix))
        assertFalse(OpenFluxOwnPageSession.admissible(prefix + "я".repeat(5000) + suffix))
        assertFalse(OpenFluxOwnPageSession.admissible(prefix + '\ud800' + suffix))
    }
    @Test fun trailingPayloadLiteralNulAndMissingReplyAreNotSubmitted() {
        val session = OpenFluxOwnPageSession { true }; var offers = 0
        assertFalse(session.submitAfterUserAction { offers++; true })
        for (value in listOf("{\"v\":1}{}", "{\"v\":1}\u0000", "{\"v\":1}tail", "{\"v\":")) {
            assertFalse(session.stage(value))
        }
        assertEquals(0, offers); assertTrue(session.stage("{\"v\":1}"))
    }
    @Test fun retiredSlotCannotAcceptAnotherPayloadOrChangeAnotherSessionsReply() {
        val old = OpenFluxOwnPageSession { true }; val replacement = OpenFluxOwnPageSession { true }
        assertTrue(old.stage("{\"v\":\"old\"}")); old.close()
        assertTrue(replacement.stage("{\"v\":\"new\"}"))
        assertFalse(old.submitAfterUserAction { error("retired") })
        assertTrue(replacement.submitAfterUserAction { it == "{\"v\":\"new\"}" })
    }
    @Test fun asynchronousAttemptBlocksDuplicateAndStaleCompletionCannotFinishManualRetry() {
        val session = OpenFluxOwnPageSession { true }
        assertTrue(session.stage("{\"v\":\"synthetic\"}"))
        val first = session.beginSubmissionAfterUserAction()!!
        assertNull(session.beginSubmissionAfterUserAction()); assertTrue(session.submissionCurrent(first))
        assertEquals(false, session.finishSubmission(first, false))
        val retry = session.beginSubmissionAfterUserAction()!!
        assertNotSame(first, retry); assertEquals(first.payload, retry.payload)
        assertNull(session.finishSubmission(first, true)); assertTrue(session.submissionCurrent(retry))
        assertEquals(true, session.finishSubmission(retry, true))
        assertNull(session.finishSubmission(retry, false)); assertNull(session.beginSubmissionAfterUserAction())
        assertEquals("OpenFluxOwnSubmission(redacted)", first.toString())
    }
    @Test fun asynchronousCloseOrLostOwnerRejectsLateSuccessAndPendingQueueGuard() {
        for (close in listOf(false, true)) {
            var live = true; val session = OpenFluxOwnPageSession { live }
            assertTrue(session.stage("{\"v\":true}")); val attempt = session.beginSubmissionAfterUserAction()!!
            if (close) session.close() else live = false
            assertFalse(session.submissionCurrent(attempt)); assertNull(session.finishSubmission(attempt, true))
            live = true; assertNull(session.beginSubmissionAfterUserAction()); assertFalse(session.stage("{\"v\":false}"))
        }
    }
    @Test fun asynchronousCompletionFromAnotherSessionCannotAdmitOrClearCurrentAttempt() {
        val first = OpenFluxOwnPageSession { true }; val second = OpenFluxOwnPageSession { true }
        assertTrue(first.stage("{\"v\":1}")); assertTrue(second.stage("{\"v\":2}"))
        val old = first.beginSubmissionAfterUserAction()!!; val current = second.beginSubmissionAfterUserAction()!!
        assertNull(second.finishSubmission(old, true)); assertTrue(second.submissionCurrent(current))
        assertEquals(true, second.finishSubmission(current, true)); first.close()
        assertNull(first.finishSubmission(old, true))
    }
}
