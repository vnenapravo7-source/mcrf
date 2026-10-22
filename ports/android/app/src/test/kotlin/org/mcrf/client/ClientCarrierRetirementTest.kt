package org.mcrf.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ClientCarrierRetirementTest {
    private class Child : Process() {
        @Volatile var alive = true
        var code = 0
        val commands = ByteArrayOutputStream()
        val stopWritten = CountDownLatch(1)
        var writeFailure: Exception? = null
        val waits = mutableListOf<Long>()
        var destroys = 0
        var kills = 0
        var onWait: (Long) -> Boolean = { !alive }
        var onDestroy: () -> Unit = {}
        var onKill: () -> Process = { this }
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                writeFailure?.let { throw it }
                commands.write(value)
                if(value==10&&commands.toString("US-ASCII").endsWith("STOP\n"))stopWritten.countDown()
            }
        }
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun isAlive() = alive
        override fun waitFor(): Int = error("Only bounded waits are allowed")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            assertEquals(TimeUnit.SECONDS, unit)
            waits.add(timeout)
            return onWait(timeout)
        }
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException()
            return code
        }
        override fun destroy() { destroys++; onDestroy() }
        override fun destroyForcibly(): Process { kills++; return onKill() }
    }

    @Test fun normalExitZeroAndOneKeepGraceAndNeverForce() {
        for (code in 0..1) for (grace in listOf(3L, 22L)) {
            val child = Child().apply {
                this.code = code
                onWait = { assertTrue(stopWritten.await(5,TimeUnit.SECONDS));alive = false; true }
            }
            val retirement = ClientCarrierRetirement(child, grace) { fail("No retry needed") }
            retirement.close()
            assertTrue(retirement.requested)
            assertTrue(retirement.complete)
            assertTrue(retirement.normalExit)
            assertFalse(retirement.forced)
            assertEquals("STOP\n", child.commands.toString("US-ASCII"))
            assertEquals(listOf(grace), child.waits)
            assertEquals(0, child.destroys + child.kills)
        }
    }

    @Test fun alreadyDeadAndRepeatedCloseDoNotWriteOrKill() {
        val child = Child().apply { alive = false; code = 1 }
        val retirement = ClientCarrierRetirement(child, 3) { fail("No retry needed") }
        repeat(3) { retirement.close() }
        assertTrue(retirement.complete)
        assertTrue(retirement.normalExit)
        assertEquals(0, child.commands.size())
        assertEquals(emptyList<Long>(), child.waits)
        assertEquals(0, child.destroys + child.kills)
    }

    @Test fun waitSuccessAndReturnedDeadProcessNeverInventOwnedChildDeath() {
        val child = Child()
        val unrelated = Child().apply { alive = false }
        child.onWait = { true } // Deliberately lies while isAlive remains true.
        child.onKill = { unrelated }
        val delays = mutableListOf<Long>()
        lateinit var retirement: ClientCarrierRetirement
        retirement = ClientCarrierRetirement(child, 3) { delay ->
            assertTrue(child.alive)
            assertTrue(retirement.requested)
            assertFalse(retirement.complete)
            assertFalse(retirement.normalExit)
            assertTrue(retirement.forced)
            delays.add(delay)
            if (delays.size == 9) child.alive = false
        }
        retirement.close()
        assertEquals(listOf(100L, 200L, 400L, 800L, 1600L, 3200L, 3200L, 3200L, 3200L), delays)
        assertEquals(1, child.destroys)
        assertEquals(9, child.kills)
        assertEquals(listOf(3L, 1L) + List(9) { 3L }, child.waits)
        assertTrue(retirement.complete)
        assertFalse(retirement.normalExit)
        retirement.close()
        assertEquals(9, child.kills)
    }

    @Test fun falseWaitWithActualDeathIsAcceptedWithoutKill() {
        val child = Child().apply { onWait = { alive = false; false } }
        val retirement = ClientCarrierRetirement(child, 3)
        retirement.close()
        assertTrue(retirement.normalExit)
        assertEquals(0, child.destroys + child.kills)
    }

    @Test fun failedDestroyAndRepeatedKillIoExceptionsRetainChildUntilDeath() {
        val child = Child()
        child.onDestroy = { throw IOException("destroy failed") }
        child.onKill = {
            if (child.kills < 4) throw IOException("kill failed")
            child.alive = false
            child
        }
        var pauses = 0
        lateinit var retirement: ClientCarrierRetirement
        retirement = ClientCarrierRetirement(child, 3) {
            assertTrue(child.alive)
            assertFalse(retirement.complete)
            assertTrue(retirement.forced)
            pauses++
        }
        retirement.close()
        assertEquals(3, pauses)
        assertEquals(4, child.kills)
        assertTrue(retirement.complete)
        assertTrue(retirement.forced)
        assertFalse(retirement.normalExit)
    }

    @Test fun stopIoExceptionStillKillsAndWaitsForActualDeath() {
        val child = Child().apply {
            writeFailure = IOException("closed pipe")
            onKill = { alive = false; this }
        }
        val retirement = ClientCarrierRetirement(child, 22)
        retirement.close()
        // A broken/full stdin must not skip native/grandchild cleanup grace.
        assertEquals(listOf(22L, 1L, 3L), child.waits)
        assertTrue(retirement.complete)
        assertTrue(retirement.forced)
        assertFalse(retirement.normalExit)
    }

    @Test fun interruptDuringGraceIsPreservedOnlyAfterActualDeath() {
        val child = Child().apply {
            onWait = { if (waits.size == 1) throw InterruptedException(); !alive }
            onKill = { alive = false; this }
        }
        try {
            val retirement = ClientCarrierRetirement(child, 3)
            retirement.close()
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(retirement.complete)
            assertTrue(retirement.forced)
            assertFalse(retirement.normalExit)
        } finally { Thread.interrupted() }
    }

    @Test fun preexistingInterruptDoesNotSkipCleanupAndSurvivesRepeatedClose() {
        val child = Child().apply {
            onWait = { assertFalse(Thread.currentThread().isInterrupted); alive = false; true }
        }
        try {
            Thread.currentThread().interrupt()
            val retirement = ClientCarrierRetirement(child, 3)
            retirement.close()
            assertTrue(retirement.complete)
            assertTrue(Thread.currentThread().isInterrupted)
            retirement.close()
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun interruptDuringForcedWaitAndBackoffNeverAcknowledgesLiveChild() {
        val child = Child().apply {
            onWait = { if (kills > 0) throw InterruptedException(); false }
        }
        var pauses = 0
        lateinit var retirement: ClientCarrierRetirement
        retirement = ClientCarrierRetirement(child, 3) {
            assertFalse(retirement.complete)
            assertTrue(child.alive)
            assertFalse(Thread.currentThread().isInterrupted)
            if (++pauses == 1) throw InterruptedException()
            child.alive = false
        }
        try {
            retirement.close()
            assertEquals(2, pauses)
            assertTrue(retirement.complete)
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(retirement.normalExit)
        } finally { Thread.interrupted() }
    }

    @Test fun destroyFollowedByExitZeroStillPermanentlyMarksForced() {
        val child = Child().apply { onDestroy = { alive = false } }
        val retirement = ClientCarrierRetirement(child, 3)
        retirement.close()
        assertTrue(retirement.complete)
        assertTrue(retirement.forced)
        assertFalse(retirement.normalExit)
        retirement.close()
        assertTrue(retirement.forced)
        assertEquals(1, child.destroys)
        assertEquals(0, child.kills)
    }

    @Test fun unexpectedNormalExitCodeCannotReportProviderSuccess() {
        val child = Child().apply { code = 2; alive = false }
        val retirement = ClientCarrierRetirement(child, 3)
        retirement.close()
        assertTrue(retirement.complete)
        assertFalse(retirement.forced)
        assertFalse(retirement.normalExit)
    }

    @Test fun waitIoExceptionCannotReleaseALiveChild() {
        val child = Child().apply { onWait = { throw IOException("wait failed") } }
        var attempts = 0
        lateinit var retirement: ClientCarrierRetirement
        retirement = ClientCarrierRetirement(child, 3) {
            assertTrue(child.alive)
            assertFalse(retirement.complete)
            if (++attempts == 3) child.alive = false
        }
        retirement.close()
        assertEquals(3, attempts)
        assertTrue(retirement.complete)
        assertFalse(retirement.normalExit)
    }

    @Test fun realProviderReaderAcceptsOnlyUnforcedNormalDeathAndCompletedDrain() {
        val fields = listOf("mailru_socketio_ready", "mailru_auth_write_ok", "mailru_auth_write_failed",
            "mailru_auth_changes_seen", "mailru_ack_write_ok", "mailru_ack_write_failed",
            "mailru_fallback_ready", "mailru_chat_write_ok", "mailru_chat_write_failed",
            "mailru_peer_chat_decoded", "mailru_read_closed", "classic_decrypt_ok",
            "classic_decrypt_failed", "classic_frame_accepted", "classic_frame_rejected")
        val record = "MCRF_PROVIDER_STAGE_V1 " + fields.joinToString(",", "{", "}") { "\"$it\":false" }
        for (force in listOf(false, true)) for (drained in listOf(false, true)) {
            val child = Child().apply {
                code = 1
                if (force) onKill = { alive = false; this }
                else onWait = { alive = false; true }
            }
            val retirement = ClientCarrierRetirement(child, 3)
            val reader = ClientOpenFluxProviderStage()
            reader.offer(record.toByteArray(Charsets.US_ASCII))
            retirement.close()
            val result = reader.finish(retirement.normalExit, drained)
            assertEquals(!force && drained, result != null)
            if (result != null) assertEquals(fields.associateWith { false }, result.events)
            retirement.close()
            assertNull(reader.finish(retirement.normalExit, true))
        }
    }

    @Test fun concurrentClosesAndDependentReleaseRemainPendingUntilExactChildDies() {
        val child = Child()
        val pending = CountDownLatch(1)
        val allowDeath = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val retirement = ClientCarrierRetirement(child, 3) {
            pending.countDown()
            assertTrue(allowDeath.await(5, TimeUnit.SECONDS))
            child.alive = false
        }
        val first = Thread { retirement.close(); released.set(true) }
        val second = Thread { retirement.close() }
        first.start()
        try {
            assertTrue(pending.await(5, TimeUnit.SECONDS))
            second.start()
            assertTrue(child.alive)
            assertFalse(retirement.complete)
            assertFalse(released.get())
        } finally {
            allowDeath.countDown()
            first.join(5000)
            second.join(5000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(retirement.complete)
        assertTrue(released.get())
        assertEquals(1, child.kills)
    }
}
