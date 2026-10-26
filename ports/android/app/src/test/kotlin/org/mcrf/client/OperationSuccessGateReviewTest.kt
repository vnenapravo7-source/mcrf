package org.mcrf.client

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OperationSuccessGateReviewTest {
    @Test fun busyRejectionDoesNotCloseDraftButRestoresControls() {
        val gate = OperationSuccessGate(); var closed = false; var enabled = false
        gate.complete({ closed = true }, { enabled = true })
        assertFalse(closed); assertTrue(enabled)
    }
    @Test fun successfulSaveClosesExactlyOnceAndPreservesReturnValue() {
        val gate = OperationSuccessGate(); var closed = 0; var cleanups = 0
        assertEquals("saved", gate.execute { "saved" })
        repeat(2) { gate.complete({ closed++ }, { cleanups++ }) }
        assertEquals(1, closed); assertEquals(2, cleanups)
    }
    @Test fun failedWriteRetainsDraftAndReenablesControls() {
        val gate = OperationSuccessGate(); var closed = false; var enabled = false
        val expected = IOException("fixture")
        try { gate.execute<Unit> { throw expected }; fail("Must throw") }
        catch (error: IOException) { assertSame(expected, error) }
        gate.complete({ closed = true }, { enabled = true })
        assertFalse(closed); assertTrue(enabled)
    }
    @Test fun cancelledOperationDoesNotDismissEditor() {
        val gate = OperationSuccessGate(); var closed = false
        try { gate.execute<Unit> { throw CancellationException() }; fail("Must cancel") }
        catch (_: CancellationException) { }
        gate.complete({ closed = true })
        assertFalse(closed)
    }
    @Test fun validationRejectionDoesNotDismissEditor() {
        val gate = OperationSuccessGate(); var closed = false
        try { gate.execute<Unit> { require(false) }; fail("Must reject") }
        catch (_: IllegalArgumentException) { }
        gate.complete({ closed = true })
        assertFalse(closed)
    }
    @Test fun cleanupSurvivesSuccessCallbackFailure() {
        val gate = OperationSuccessGate(); var enabled = false
        gate.execute { Unit }
        try { gate.complete({ throw IllegalStateException() }, { enabled = true }); fail("Must throw") }
        catch (_: IllegalStateException) { }
        assertTrue(enabled)
        gate.complete({ fail("Success already consumed") })
    }
}
