package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Pending common gate. Fake numeric sources only; no Android reads/network or provider calls. */
class ResourceMonitorSamplerTest {
    @Test fun constructingAndRecordingWhileInactiveDoNotReadOrStartRecording() {
        val reads = AtomicInteger()
        val sampler = ResourceMonitorSampler(
            source = ResourceMonitorSource { reads.incrementAndGet(); ResourceMonitorReading(0) },
            clock = { 0L }, changed = {},
        )
        try {
            sampler.startRecording()
            sampler.stopRecording()
            sampler.resetRecording()
            sampler.stop()
            assertEquals(0, reads.get())
            assertEquals(ResourceMonitorSnapshot(), sampler.snapshot())
        } finally { sampler.close() }
        sampler.start()
        assertEquals(0, reads.get())
    }

    @Test fun blockedReadCannotBlockStopOrPublishAfterClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val owner = Thread.currentThread()
        val sampler = ResourceMonitorSampler(
            source = ResourceMonitorSource {
                assertNotSame(owner, Thread.currentThread())
                entered.countDown()
                // Model an OS read that does not react to interruption immediately.
                try {
                    while (release.count > 0) {
                        try { release.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { /* finish owned read */ }
                    }
                    ResourceMonitorReading(0, processPssBytes = 1234)
                } finally { exited.countDown() }
            }, clock = { 0L }, changed = { delivered.countDown() },
        )
        try {
            sampler.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // These must return while the read is still blocked.
            sampler.startRecording()
            sampler.stop()
            sampler.close()
            assertFalse(sampler.snapshot().recording.active)
            release.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertFalse(delivered.await(150, TimeUnit.MILLISECONDS))
            assertNull(sampler.snapshot().processPssBytes)
        } finally { release.countDown(); sampler.close() }
    }

    @Test fun sourceFailurePublishesUnavailableNotFakeZeros() {
        val delivered = CountDownLatch(1)
        val sampler = ResourceMonitorSampler(
            source = ResourceMonitorSource { throw IllegalStateException("unavailable") },
            clock = { 1000L }, changed = { delivered.countDown() },
        )
        try {
            sampler.start()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertNull(sampler.snapshot().processCpuPercent)
            assertNull(sampler.snapshot().processPssBytes)
            assertNull(sampler.snapshot().uidRxBytesPerSecond)
        } finally { sampler.close() }
    }
}
