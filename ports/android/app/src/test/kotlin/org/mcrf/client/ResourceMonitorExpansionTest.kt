package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pending main's common verification gate. Pure fake samples; no OS/network/device access. */
class ResourceMonitorExpansionTest {
    @Test fun sharedReadGateAdmitsOnlyOneConcurrentOwnerPerBudgetInterval() {
        val gate = ResourceMonitorReadGate()
        val owners = List(8) { Any() }
        val workers = Executors.newFixedThreadPool(owners.size)
        try {
            repeat(32) { round ->
                val now = 1000L + round * 1000L
                val ready = CountDownLatch(owners.size)
                val release = CountDownLatch(1)
                val results = owners.map { owner ->
                    workers.submit<Boolean> {
                        ready.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        // Different sampler locks must still share one atomic admission.
                        synchronized(owner) { gate.admit(now) }
                    }
                }
                try {
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                } finally { release.countDown() }
                assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
                assertFalse(gate.admit(now + 999L))
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun recordingCommandsRejectObservationsAlreadyInFlight() {
        for (reset in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val delivered = CountDownLatch(1)
            val sampler = ResourceMonitorSampler(
                source = ResourceMonitorSource {
                    entered.countDown()
                    try {
                        check(release.await(2, TimeUnit.SECONDS))
                        ResourceMonitorReading(1000, uidRxBytes = 900, processPssBytes = 4096)
                    } finally { exited.countDown() }
                },
                clock = { 1000L }, changed = { delivered.countDown() },
            )
            try {
                sampler.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                sampler.startRecording()
                if (reset) { sampler.resetRecording(); sampler.startRecording() }
                release.countDown()
                assertTrue(exited.await(2, TimeUnit.SECONDS))
                assertFalse(delivered.await(150, TimeUnit.MILLISECONDS))
                assertTrue(sampler.snapshot().recording.active)
                assertEquals(0L, sampler.snapshot().recording.samples)
                assertNull(sampler.snapshot().processPssBytes)
            } finally { release.countDown(); sampler.close() }
        }
    }

    @Test fun categoriesCountersAndConnectionStateRemainIndependentOfSessionDeltas() {
        val model = ResourceMonitorModel()
        model.startRecording()
        model.accept(ResourceMonitorReading(1000, uidRxBytes = 900000, uidTxBytes = 800000,
            processPssBytes = 8192, processRssBytes = 16384))
        val value = model.accept(ResourceMonitorReading(2000, uidRxBytes = 900500, uidTxBytes = 800100,
            processPssBytes = 4096, processRssBytes = 12288,
            processMemory = ResourceMonitorMemory(1024, 2048, null, 0, -1, 512, 256),
            processThreads = 7, mcrfSessionPresent = true, mcrfConnected = false))
        assertEquals(900500L, value.uidRxBytes)
        assertEquals(800100L, value.uidTxBytes)
        assertEquals(500L, value.recording.rxBytes)
        assertEquals(100L, value.recording.txBytes)
        assertEquals(1024L, value.processMemory.javaHeapBytes)
        assertEquals(2048L, value.processMemory.nativeHeapBytes)
        assertNull(value.processMemory.graphicsBytes)
        assertEquals(0L, value.processMemory.codeBytes)
        assertNull(value.processMemory.stackBytes)
        assertEquals(7L, value.processThreads)
        assertEquals(true, value.mcrfSessionPresent)
        assertEquals(false, value.mcrfConnected)
        assertEquals(8192L, value.recording.startProcessPssBytes)
        assertEquals(4096L, value.recording.currentProcessPssBytes)
        assertEquals(8192L, value.recording.peakProcessPssBytes)
        assertEquals(16384L, value.recording.peakProcessRssBytes)
    }

    @Test fun missingInitialMemoryCannotBecomeAFabricatedDeltaAndCurrentMissingStaysMissing() {
        val model = ResourceMonitorModel()
        model.startRecording()
        model.accept(ResourceMonitorReading(1000))
        var value = model.accept(ResourceMonitorReading(2000, processPssBytes = 4096))
        assertNull(value.recording.startProcessPssBytes)
        assertEquals(4096L, value.recording.currentProcessPssBytes)
        value = model.accept(ResourceMonitorReading(3000))
        assertNull(value.recording.currentProcessPssBytes)
        assertEquals(4096L, value.recording.peakProcessPssBytes)
        model.suspendSampling()
        assertNull(model.snapshot.processThreads)
        assertNull(model.snapshot.mcrfSessionPresent)
        assertEquals(ResourceMonitorMemory(), model.snapshot.processMemory)
        assertFalse(model.snapshot.recording.active)
        assertEquals(4096L, model.snapshot.recording.peakProcessPssBytes)
    }

    @Test fun boundedProcParsingAndAndroidMemoryValuesDoNotConvertUnavailableToZero() {
        assertEquals(1024L, ResourceMonitorProc.kilobytes("1"))
        assertEquals(0L, ResourceMonitorProc.kilobytes("0"))
        for (value in listOf(null, "", "-1", "NaN", Long.MAX_VALUE.toString()))
            assertNull(ResourceMonitorProc.kilobytes(value))
        assertEquals(17L, ResourceMonitorProc.threads("Name:\tignored\nThreads:\t17\n"))
        for (status in listOf("", "Threads: 0", "Threads: -1", "Threads: many", "Threads: 17 extra"))
            assertNull(ResourceMonitorProc.threads(status))
    }

    @Test fun onlyMiniRemainsAndDetailedMetricsStayInTheMainWindowPresentation() {
        assertEquals(listOf(ResourceMonitorSize.MINIATURE), ResourceMonitorSize.entries.toList())
        assertEquals(128, ResourceMonitorSize.MINIATURE.widthDp)
        val value = ResourceMonitorSnapshot(processCpuPercent = 120.0, processPssBytes = 1048576)
        assertEquals(2, ResourceMonitorText.hud(value).lines().size)
        val rows = ResourceMonitorPresentation.sections(value, true).flatMap { it.rows }.associateBy { it.id }
        assertEquals("Недоступно", rows.getValue("workers").value)
        assertEquals("—", rows.getValue("rss").value)
        assertEquals(ResourceMonitorText.percent(120.0), rows.getValue("cpu").value)
    }

    @Test fun revocationScreenOffOrLockAlwaysClosesOverlayEvenWithOtherGatesOpen() {
        assertTrue(ResourceMonitorOverlayPolicy.allowed(permission = true, interactive = true, locked = false))
        assertFalse(ResourceMonitorOverlayPolicy.allowed(permission = false, interactive = true, locked = false))
        assertFalse(ResourceMonitorOverlayPolicy.allowed(permission = true, interactive = false, locked = false))
        assertFalse(ResourceMonitorOverlayPolicy.allowed(permission = true, interactive = true, locked = true))
    }

    @Test fun cancelledAndOlderQueuedStartsCannotReviveOverlayOrReplaceNewConsent() {
        val gate = ResourceMonitorOverlayStartGate()
        val cancelled = gate.request()
        gate.cancel()
        assertFalse(gate.accepts(cancelled))
        assertFalse(gate.requested)
        val current = gate.request()
        assertFalse(gate.accepts(cancelled))
        assertTrue(gate.accepts(current))
        gate.cancel()
        // Unlock or a restored permission is not another explicit request.
        assertTrue(ResourceMonitorOverlayPolicy.allowed(permission = true, interactive = true, locked = false))
        assertFalse(gate.accepts(current))
        val next = gate.request()
        assertFalse(gate.accepts(current))
        assertTrue(gate.accepts(next))
    }
}
