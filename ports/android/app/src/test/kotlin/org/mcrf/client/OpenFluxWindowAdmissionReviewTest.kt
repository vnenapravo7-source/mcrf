package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Source models only; not real Activity pause/rotation or an IPC cancellation receipt. */
class OpenFluxWindowAdmissionReviewTest {
    @Test fun noPermitBeforeForegroundOrAfterPause() {
        val gate = OpenFluxWindowAdmission()
        assertNull(gate.capture()); assertFalse(gate.current(null))
        gate.resume(); val permit = gate.capture()!!; assertTrue(gate.current(permit))
        gate.pause(); assertNull(gate.capture()); assertFalse(gate.current(permit))
    }
    @Test fun resumeCannotReviveActionQueuedBeforePause() {
        val gate = OpenFluxWindowAdmission(); gate.resume(); val old = gate.capture()!!
        gate.pause(); gate.resume(); val fresh = gate.capture()!!
        assertFalse(gate.current(old)); assertTrue(gate.current(fresh))
        assertEquals("OpenFluxWindowPermit(redacted)", fresh.toString())
    }
    @Test fun closeIsTerminalDespiteRepeatedLifecycleEvents() {
        val gate = OpenFluxWindowAdmission(); gate.resume(); val old = gate.capture()!!
        gate.close(); gate.pause(); gate.resume(); gate.close()
        assertNull(gate.capture()); assertFalse(gate.current(old))
    }
    @Test fun permitFromOtherWindowCannotAuthorizeReplacement() {
        val old = OpenFluxWindowAdmission(); val replacement = OpenFluxWindowAdmission()
        old.resume(); replacement.resume(); val permit = old.capture()!!
        assertFalse(replacement.current(permit)); old.close()
        assertTrue(replacement.current(replacement.capture()))
    }
    @Test fun duplicateResumeKeepsCurrentEpochButRepeatedPauseDoesNotReopen() {
        val gate = OpenFluxWindowAdmission(); gate.resume(); val permit = gate.capture()!!
        gate.resume(); assertTrue(gate.current(permit))
        gate.pause(); gate.pause(); assertFalse(gate.current(permit)); assertNull(gate.capture())
    }
    @Test fun queuedSubmissionAfterPauseRejectedWithoutOfferAndNeedsFreshManualAttempt() {
        val gate = OpenFluxWindowAdmission(); gate.resume()
        val session = OpenFluxOwnPageSession { true }; assertTrue(session.stage("{\"answer\":true}"))
        val attempt = session.beginSubmissionAfterUserAction()!!; val permit = gate.capture()
        gate.pause(); gate.resume(); var offers = 0
        val accepted = gate.current(permit) && session.submissionCurrent(attempt) && run { offers++; true }
        assertEquals(false, session.finishSubmission(attempt, accepted)); assertEquals(0, offers)
        val retry = session.beginSubmissionAfterUserAction()!!; val newPermit = gate.capture()
        assertTrue(gate.current(newPermit) && session.submissionCurrent(retry))
        assertEquals(true, session.finishSubmission(retry, run { offers++; true })); assertEquals(1, offers)
    }
}
