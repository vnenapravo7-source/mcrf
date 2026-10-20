package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WarpTraceSessionTest {
    @Test fun staleCancelAndCompletionCannotTouchNewCandidate() {
        val state=WarpTraceSession();val old=UUID.randomUUID().toString();val next=UUID.randomUUID().toString()
        assertTrue(state.begin(old));assertFalse(state.begin(next));assertTrue(state.finish(old));assertTrue(state.begin(next))
        assertFalse(state.cancel(old));assertFalse(state.finish(old));assertFalse(state.cancelled(next));assertEquals(next,state.activeId())
    }
    @Test fun cancelledCandidateAndDestroyedInstanceFailClosed() {
        val state=WarpTraceSession();val id=UUID.randomUUID().toString();state.begin(id)
        assertTrue(state.cancel(id));assertTrue(state.cancelled(id));state.close();state.finish(id)
        assertFalse(state.begin(UUID.randomUUID().toString()))
    }
    @Test fun malformedRequestIdIsRejected() {
        assertThrows(Exception::class.java) {WarpTraceSession().begin("not-a-session")}
    }
}
