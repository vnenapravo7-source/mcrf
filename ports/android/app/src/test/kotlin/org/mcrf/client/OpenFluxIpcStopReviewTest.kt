package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** New-only final common batch. Memory pipe, not an Android/native process proof. */
class OpenFluxIpcStopReviewTest {
    private fun command(value: String) = "OPENFLUX_CAPTCHA_REPLY|$value\n".toByteArray(Charsets.US_ASCII)
    private class Blocked : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        override fun write(value: Int) = error("Array write expected")
        override fun write(value: ByteArray) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            output.write(value)
        }
    }
    private fun withBlocked(checks: (CarrierIpcWriter, Blocked) -> Unit) {
        val stream = Blocked(); val writer = CarrierIpcWriter(stream)
        try {
            assertTrue(writer.offer(command("already-in-flight")))
            assertTrue(stream.entered.await(5, TimeUnit.SECONDS))
            checks(writer, stream)
        } finally {
            writer.requestStopDiscardingPending(); stream.release.countDown()
            assertTrue(writer.awaitCompletion(5000).writerExited)
        }
    }

    @Test fun stopDiscardsQueuedRepliesButRetainsInFlightWriteAndReservedStop() = withBlocked { writer, stream ->
        val caller = command("must-not-be-written")
        assertTrue(writer.offer(caller))
        writer.requestStopDiscardingPending(); writer.requestStopDiscardingPending()
        assertTrue(caller.contentEquals(command("must-not-be-written"))) // Wipe owned copy, not caller data.
        assertFalse(writer.offer(command("late")))
        assertFalse(writer.awaitCompletion(0).writerExited)
        stream.release.countDown()
        val result = writer.awaitCompletion(5000)
        assertTrue(result.writerExited); assertTrue(result.stopWritten); assertFalse(result.failed)
        assertEquals("OPENFLUX_CAPTCHA_REPLY|already-in-flight\nSTOP\n", stream.output.toString("US-ASCII"))
    }

    @Test fun strongerStopCanDiscardPendingAfterOrdinarySealWithoutReopeningAdmission() = withBlocked { writer, stream ->
        assertTrue(writer.offer(command("pending-before-seal")))
        writer.requestStop(); writer.requestStopDiscardingPending(); writer.requestStop()
        assertFalse(writer.offer(command("late")))
        stream.release.countDown()
        val result = writer.awaitCompletion(5000)
        assertTrue(result.writerExited); assertTrue(result.stopWritten); assertFalse(result.failed)
        assertEquals("OPENFLUX_CAPTCHA_REPLY|already-in-flight\nSTOP\n", stream.output.toString("US-ASCII"))
    }

    @Test fun confirmedChildDeathAfterDiscardDoesNotWriteStopOrInventGracefulExit() = withBlocked { writer, stream ->
        assertTrue(writer.offer(command("pending")))
        writer.requestStopDiscardingPending(); writer.childExited(); writer.requestStopDiscardingPending()
        stream.release.countDown()
        val result = writer.awaitCompletion(5000)
        assertTrue(result.writerExited); assertFalse(result.stopWritten); assertFalse(result.failed)
        assertEquals("OPENFLUX_CAPTCHA_REPLY|already-in-flight\n", stream.output.toString("US-ASCII"))
    }
}
