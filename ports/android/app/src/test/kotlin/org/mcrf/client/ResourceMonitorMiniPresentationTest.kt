package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Pure presentation cases; intentionally not executed before the main P0 verification gate. */
class ResourceMonitorMiniPresentationTest {
    @Test fun miniIsBoundedBy128DpAndAvailableViewportAcrossDensities() {
        assertEquals(1, ResourceMonitorLayout.widthPx(-10, 1f))
        assertEquals(1, ResourceMonitorLayout.widthPx(0, 1f))
        assertEquals(72, ResourceMonitorLayout.widthPx(72, 1f))
        assertEquals(128, ResourceMonitorLayout.widthPx(128, 1f))
        assertEquals(128, ResourceMonitorLayout.widthPx(160, 1f))
        assertEquals(128, ResourceMonitorLayout.widthPx(1024, 1f))
        assertEquals(11f, ResourceMonitorSize.MINIATURE.textSp, 0f)
        for (density in listOf(1f, 1.5f, 2f, 2.75f, 4f)) {
            for (viewport in listOf(8, 80, 128, 160, 320, 768)) {
                val available = ((viewport - 16) * density).toInt()
                val width = ResourceMonitorLayout.widthPx(available, density)
                assertTrue(width >= 1)
                assertTrue(width <= available.coerceAtLeast(1))
                assertTrue(width <= (128 * density).toInt())
            }
        }
        assertEquals(224, ResourceMonitorLayout.widthPx(224, 2f))
        assertEquals(256, ResourceMonitorLayout.widthPx(640, 2f))
    }

    @Test fun fullInlineSectionsKeepMissingDifferentFromZeroAndSessionFlagsIndependent() {
        val value = ResourceMonitorSnapshot(processCpuPercent = 0.0, processPssBytes = 0,
            processMemory = ResourceMonitorMemory(javaHeapBytes = 0, nativeHeapBytes = 1048576),
            processThreads = 7, uidRxBytes = 4096, uidTxBytes = 0,
            mcrfSessionPresent = true, mcrfConnected = false)
        val sections = ResourceMonitorPresentation.sections(value, true)
        assertEquals(5, sections.size)
        val rows = sections.flatMap { it.rows }
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val byId = rows.associateBy { it.id }
        assertEquals(ResourceMonitorText.bytes(0), byId.getValue("pss").value)
        assertEquals("—", byId.getValue("rss").value)
        assertEquals(ResourceMonitorText.bytes(0), byId.getValue("java").value)
        assertEquals(ResourceMonitorText.bytes(1048576), byId.getValue("native").value)
        for (id in listOf("graphics", "code", "stack", "private-other", "system", "rx-rate", "tx-rate"))
            assertEquals(id, "—", byId.getValue(id).value)
        assertEquals("7", byId.getValue("threads").value)
        assertEquals("Да", byId.getValue("session").value)
        assertEquals("Нет", byId.getValue("connected").value)
        assertEquals("Недоступно", byId.getValue("workers").value)
        assertEquals(ResourceMonitorText.bytes(4096), byId.getValue("rx-total").value)
        assertEquals(ResourceMonitorText.bytes(0), byId.getValue("tx-total").value)
    }

    @Test fun frozenRecordingShowsSignedDeltaPartialTotalsAndUnavailableBaselineHonestly() {
        val recording = ResourceMonitorRecording(samples = 2, observedMs = 1500, rxBytes = 1024, rxPartial = true,
            txPartial = true, startProcessPssBytes = 2097152, currentProcessPssBytes = 1048576,
            peakProcessPssBytes = 3145728, peakProcessRssBytes = 4194304)
        fun rows(session: ResourceMonitorRecording) = ResourceMonitorPresentation.sections(
            ResourceMonitorSnapshot(recording = session), false).flatMap { it.rows }.associateBy { it.id }
        val frozen = rows(recording)
        assertEquals("Монитор выключен", frozen.getValue("recording-state").value)
        assertEquals("1 с", frozen.getValue("recording-time").value)
        assertEquals("2", frozen.getValue("recording-samples").value)
        assertTrue(frozen.getValue("recording-rx").value.endsWith(" (частично)"))
        assertEquals("—", frozen.getValue("recording-tx").value)
        assertTrue(frozen.getValue("recording-pss-delta").value.startsWith("-"))
        assertEquals(ResourceMonitorText.bytes(3145728), frozen.getValue("recording-pss-peak").value)
        assertEquals("—", rows(recording.copy(startProcessPssBytes = null)).getValue("recording-pss-delta").value)
        assertEquals(ResourceMonitorActions(false, false, true), ResourceMonitorPresentation.actions(recording, false))
    }

    @Test fun recordingActionsCannotStartAnOffMonitorAndMiniDoesNotLoseTheRecordingMark() {
        assertEquals(ResourceMonitorActions(false, false, false), ResourceMonitorPresentation.actions(ResourceMonitorRecording(), false))
        assertEquals(ResourceMonitorActions(true, false, false), ResourceMonitorPresentation.actions(ResourceMonitorRecording(), true))
        val value = ResourceMonitorSnapshot(recording = ResourceMonitorRecording(active = true))
        assertEquals(ResourceMonitorActions(false, true, true), ResourceMonitorPresentation.actions(value.recording, true))
        val lines = ResourceMonitorText.hud(value).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("●"))
        assertEquals("CPU — ●", lines[0])
        assertEquals("PSS —", lines[1])
    }
}
