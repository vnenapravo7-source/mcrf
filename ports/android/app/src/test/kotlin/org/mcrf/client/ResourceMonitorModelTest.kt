package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Source only: include in main's common verification batch; do not run in the HUD workstream. */
class ResourceMonitorModelTest {
    private fun reading(time: Long, cpu: Long? = 0, rx: Long? = 0, tx: Long? = 0, pss: Long? = 4096) =
        ResourceMonitorReading(time, cpu, pss, 8192, rx, tx)

    @Test fun defaultIsUnavailableAndRecordingOff() {
        val state = ResourceMonitorModel().snapshot
        assertFalse(state.recording.active)
        assertNull(state.processCpuPercent)
        assertNull(state.processPssBytes)
        assertNull(state.uidRxBytesPerSecond)
        assertNull(state.recording.rxBytes)
    }

    @Test fun firstSampleIsBaselineNotBootTrafficOrZeroCpu() {
        val model = ResourceMonitorModel()
        model.startRecording()
        val state = model.accept(reading(0, 9000, 9000000, 8000000))
        assertNull(state.processCpuPercent)
        assertNull(state.uidRxBytesPerSecond)
        assertEquals(0L, state.recording.rxBytes)
        assertEquals(0L, state.recording.txBytes)
        assertEquals(0L, state.recording.observedMs)
        assertEquals(1L, state.recording.samples)
    }

    @Test fun actualElapsedTimeAndMulticoreCpuArePreserved() {
        val model = ResourceMonitorModel()
        model.accept(reading(1000, 10, 100, 200))
        val state = model.accept(reading(3000, 4010, 4196, 2248))
        assertEquals(200.0, state.processCpuPercent!!, .0001)
        assertEquals(2048.0, state.uidRxBytesPerSecond!!, .0001)
        assertEquals(1024.0, state.uidTxBytesPerSecond!!, .0001)
        assertEquals(4096L, state.processPssBytes)
        assertEquals(8192L, state.processRssBytes)
    }

    @Test fun recordingExcludesIntervalBeforeNextObservedBaseline() {
        val model = ResourceMonitorModel()
        model.accept(reading(0, 0, 0, 0))
        model.startRecording()
        model.accept(reading(1000, 500, 10000, 20000))
        val session = model.accept(reading(2000, 600, 10300, 20400)).recording
        assertEquals(300L, session.rxBytes)
        assertEquals(400L, session.txBytes)
        assertEquals(1000L, session.observedMs)
        assertEquals(10.0, session.averageProcessCpuPercent!!, .0001)
        assertEquals(10.0, session.peakProcessCpuPercent!!, .0001)
    }

    @Test fun stopFreezesTotalsAndRestartStartsANewRecording() {
        val model = ResourceMonitorModel()
        model.startRecording()
        model.accept(reading(0))
        model.accept(reading(1000, rx = 500))
        model.stopRecording()
        val frozen = model.snapshot.recording
        assertEquals(frozen, model.accept(reading(2000, rx = 50000)).recording)
        model.startRecording()
        model.accept(reading(3000, rx = 60000))
        assertEquals(frozen.rxBytes, model.snapshot.recording.rxBytes)
        assertEquals(frozen.samples+1, model.snapshot.recording.samples)
    }

    @Test fun startWhileRecordingIsIdempotentAndResetStops() {
        val model = ResourceMonitorModel()
        model.startRecording()
        model.accept(reading(0))
        model.startRecording()
        assertEquals(10L, model.accept(reading(1000, rx = 10)).recording.rxBytes)
        model.resetRecording()
        assertEquals(ResourceMonitorRecording(active=true), model.snapshot.recording)
        assertEquals(10.0, model.snapshot.uidRxBytesPerSecond!!, .0001)
    }

    @Test fun unsupportedAndNegativeCountersAreUnavailableNotZero() {
        val model = ResourceMonitorModel()
        model.startRecording()
        model.accept(ResourceMonitorReading(0, -1, -1, -1, -1, null))
        val state = model.accept(ResourceMonitorReading(1000))
        assertNull(state.processCpuPercent)
        assertNull(state.processPssBytes)
        assertNull(state.processRssBytes)
        assertNull(state.uidRxBytesPerSecond)
        assertNull(state.uidTxBytesPerSecond)
        assertNull(state.recording.rxBytes)
        assertNull(state.recording.txBytes)
        assertTrue(state.recording.rxPartial)
        assertTrue(state.recording.txPartial)
    }

    @Test fun resetAndUnsupportedGapNeverInventBytesAndDirectionsAreIndependent() {
        val model = ResourceMonitorModel()
        model.startRecording()
        model.accept(reading(0, rx = 100, tx = 100))
        model.accept(reading(1000, rx = 200, tx = 200))
        var state = model.accept(reading(2000, rx = 50, tx = 300))
        assertNull(state.uidRxBytesPerSecond)
        assertEquals(100.0, state.uidTxBytesPerSecond!!, .0001)
        assertEquals(100L, state.recording.rxBytes)
        assertTrue(state.recording.rxPartial)
        assertFalse(state.recording.txPartial)
        model.accept(reading(3000, rx = null, tx = 400))
        model.accept(reading(4000, rx = 1000, tx = 500))
        state = model.accept(reading(5000, rx = 1100, tx = 600))
        assertEquals(200L, state.recording.rxBytes)
        assertEquals(500L, state.recording.txBytes)
    }

    @Test fun cpuRegressionAndMissingPssStayUnavailable() {
        val model = ResourceMonitorModel()
        model.startRecording()
        model.accept(reading(0, cpu = 100, pss = null))
        val session = model.accept(reading(1000, cpu = 10, pss = null)).recording
        assertNull(model.snapshot.processCpuPercent)
        assertNull(session.averageProcessCpuPercent)
        assertNull(session.peakProcessPssBytes)
        assertEquals(1.0, model.accept(reading(2000, cpu = 20)).processCpuPercent!!, .0001)
    }

    @Test fun recordingCpuAverageIsWeightedByObservedDuration() {
        val model = ResourceMonitorModel()
        model.startRecording()
        model.accept(reading(0))
        model.accept(reading(1000, cpu = 100))
        val session = model.accept(reading(4000, cpu = 1600, pss = 16384)).recording
        assertEquals(40.0, session.averageProcessCpuPercent!!, .0001)
        assertEquals(50.0, session.peakProcessCpuPercent!!, .0001)
        assertEquals(16384L, session.peakProcessPssBytes)
    }

    @Test fun detachOrBackgroundFreezesSessionAndInvalidatesLiveValues() {
        val model = ResourceMonitorModel()
        model.startRecording()
        model.accept(reading(0))
        model.accept(reading(1000, rx = 10))
        model.suspendSampling()
        val frozen = model.snapshot.recording
        assertFalse(frozen.active)
        assertNull(model.snapshot.processCpuPercent)
        assertNull(model.snapshot.processPssBytes)
        assertNull(model.snapshot.uidRxBytesPerSecond)
        assertEquals(frozen, model.accept(reading(100000, rx = 999999)).recording)
        assertNull(model.snapshot.uidRxBytesPerSecond)
    }

    @Test fun duplicateReversedAndNegativeTimestampsCannotChangeState() {
        val model = ResourceMonitorModel()
        model.startRecording()
        val expected = model.accept(reading(1000, rx = 100))
        for (time in listOf(-1L, 0L, 1000L)) assertEquals(expected, model.accept(reading(time, rx = 999)))
        assertEquals(100L, model.accept(reading(2000, rx = 200)).recording.rxBytes)
    }

    @Test fun totalsSaturateWithoutWrapping() {
        val model = ResourceMonitorModel()
        model.startRecording()
        model.accept(reading(0))
        model.accept(reading(1000, rx = Long.MAX_VALUE))
        model.accept(reading(2000, rx = 0))
        val session = model.accept(reading(3000, rx = 100)).recording
        assertEquals(Long.MAX_VALUE, session.rxBytes)
        assertTrue(session.rxPartial)
    }

    @Test fun rssParserRejectsMissingMalformedOverflowAndWrongUnits() {
        assertEquals(43008L, ResourceMonitorProc.rssBytes("Name:\tignored\nVmRSS:\t42 kB\nThreads:\t2\n"))
        for (text in listOf("", "VmSize: 42 kB", "VmRSS: -1 kB", "VmRSS: 42 MB", "VmRSS: 3", "VmRSS: ${Long.MAX_VALUE} kB"))
            assertNull(ResourceMonitorProc.rssBytes(text))
    }

    @Test fun readGateAdmitsNoMoreThanOncePerSecondAcrossRestartAndClockRegression() {
        val gate = ResourceMonitorReadGate()
        assertFalse(gate.admit(-1))
        assertTrue(gate.admit(0))
        for (time in listOf(0L, 1L, 500L, 999L)) assertFalse(gate.admit(time))
        assertTrue(gate.admit(1000))
        assertFalse(gate.admit(999))
        assertFalse(gate.admit(1999))
        assertTrue(gate.admit(2000))
        assertTrue(gate.admit(10000))
        assertFalse(gate.admit(10000))
    }
}
