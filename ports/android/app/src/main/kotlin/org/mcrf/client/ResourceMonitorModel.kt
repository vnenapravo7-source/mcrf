package org.mcrf.client

/**
 * Bounded Android adaptation of CSQTT ResourceMonitor / V2ResourceHud.
 * Original CSQTT project: amurcanov / SanityProtocol, PolyForm Noncommercial 1.0.0.
 * Required Notice: Copyright 2026 amurcanov.
 * Required Notice: Commercial use of CSQTT requires a separate written license from the licensor.
 * Required Notice: Redistributions and derivative works must retain attribution to the original
 * CSQTT project and these notices. License: https://polyformproject.org/licenses/noncommercial/1.0.0/
 * Reference: CSQTT commit 0188606a243c92a54440be09dc59a93848c463aa,
 * ui/v2/monitor/ResourceMonitor.kt and ui/v2/dialogs/V2ResourceHud.kt / V2ResourceSheet.kt.
 *
 * No history, identifiers, packet contents, destinations or disk persistence. CPU/PSS/RSS belong
 * ONLY to this process. UID counters can include same-UID subprocesses, but are not engine totals
 * (isolated/other-UID engines are excluded, VPN accounting may include multiple legs).
 */
data class ResourceMonitorMemory(
    val javaHeapBytes: Long? = null,
    val nativeHeapBytes: Long? = null,
    val graphicsBytes: Long? = null,
    val codeBytes: Long? = null,
    val stackBytes: Long? = null,
    val privateOtherBytes: Long? = null,
    val systemBytes: Long? = null,
)

/** Mini only. Legacy resource-monitor/size is deliberately neither read nor rewritten. */
enum class ResourceMonitorSize(val title: String, val widthDp: Int, val textSp: Float) {
    MINIATURE("Миниатюрный", 128, 11f),
}

internal object ResourceMonitorLayout {
    /** Caller reserves 16dp around the HUD; narrow windows shrink without restoring old sizes. */
    fun widthPx(availablePx: Int, density: Float): Int {
        require(density.isFinite() && density > 0)
        val available = availablePx.coerceAtLeast(1)
        return minOf(available, (ResourceMonitorSize.MINIATURE.widthDp * density).toInt().coerceAtLeast(1))
    }
}

/** Permission and visibility never imply consent to start; this is only an ongoing safety gate. */
internal object ResourceMonitorOverlayPolicy {
    fun allowed(permission: Boolean, interactive: Boolean, locked: Boolean) = permission && interactive && !locked
}

/** Main-thread owned explicit consent; queued service intents cannot revive a cancelled request. */
internal class ResourceMonitorOverlayStartGate {
    private var generation = 0L
    var requested = false
        private set
    fun request(): Long { requested = true; return ++generation }
    fun cancel() { requested = false; generation++ }
    fun accepts(token: Long) = requested && token == generation
}

data class ResourceMonitorReading(
    val elapsedMs: Long,
    val processCpuMs: Long? = null,
    val processPssBytes: Long? = null,
    val processRssBytes: Long? = null,
    val uidRxBytes: Long? = null,
    val uidTxBytes: Long? = null,
    val processMemory: ResourceMonitorMemory = ResourceMonitorMemory(),
    val processThreads: Long? = null,
    val mcrfSessionPresent: Boolean? = null,
    val mcrfConnected: Boolean? = null,
)

data class ResourceMonitorRecording(
    val active: Boolean = false,
    val samples: Long = 0,
    val observedMs: Long = 0,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val rxPartial: Boolean = false,
    val txPartial: Boolean = false,
    val averageProcessCpuPercent: Double? = null,
    val peakProcessCpuPercent: Double? = null,
    val peakProcessPssBytes: Long? = null,
    val startProcessPssBytes: Long? = null,
    val currentProcessPssBytes: Long? = null,
    val peakProcessRssBytes: Long? = null,
)

data class ResourceMonitorSnapshot(
    val processCpuPercent: Double? = null,
    val processPssBytes: Long? = null,
    val processRssBytes: Long? = null,
    val uidRxBytesPerSecond: Double? = null,
    val uidTxBytesPerSecond: Double? = null,
    val recording: ResourceMonitorRecording = ResourceMonitorRecording(),
    val processMemory: ResourceMonitorMemory = ResourceMonitorMemory(),
    val processThreads: Long? = null,
    val uidRxBytes: Long? = null,
    val uidTxBytes: Long? = null,
    val mcrfSessionPresent: Boolean? = null,
    val mcrfConnected: Boolean? = null,
)

/**
 * Shared across controller/service samplers and stop/restart. Admission is atomic on this
 * gate's monitor: individual sampler locks do not protect the shared read-start budget.
 * This limits read starts, not the duration or overlap of already admitted OS reads.
 */
internal class ResourceMonitorReadGate {
    private var lastStartedMs: Long? = null
    @Synchronized
    fun admit(nowMs: Long): Boolean {
        if (nowMs < 0 || lastStartedMs?.let { nowMs - it < 1000 } == true) return false
        lastStartedMs = nowMs
        return true
    }
}

/** Pure, constant-space reducer. Commands and samples are serialized by ResourceMonitorSampler. */
internal class ResourceMonitorModel {
    private var previous: ResourceMonitorReading? = null
    private var recordingPrevious: ResourceMonitorReading? = null
    private var cpuWeightMs = 0.0
    private var cpuSumMs = 0.0
    var snapshot = ResourceMonitorSnapshot()
        private set

    fun startRecording() {
        if (snapshot.recording.active) return
        recordingPrevious = null
        snapshot = snapshot.copy(recording = snapshot.recording.copy(active = true))
    }

    /** Freeze at the last observed sample, never extrapolate to a UI command's timestamp. */
    fun stopRecording() {
        recordingPrevious = null
        snapshot = snapshot.copy(recording = snapshot.recording.copy(active = false))
    }

    fun resetRecording() {
        val active=snapshot.recording.active
        recordingPrevious=null;cpuWeightMs=0.0;cpuSumMs=0.0
        snapshot = snapshot.copy(recording = ResourceMonitorRecording(active=active))
    }

    /** A detach/background interval must never be attributed to the next foreground interval. */
    fun suspendSampling() {
        stopRecording()
        previous = null
        snapshot = ResourceMonitorSnapshot(recording = snapshot.recording)
    }

    fun accept(reading: ResourceMonitorReading): ResourceMonitorSnapshot {
        if (reading.elapsedMs < 0) return snapshot
        val before = previous
        // Duplicate/out-of-order clock readings cannot change baselines or session totals.
        if (before != null && reading.elapsedMs <= before.elapsedMs) return snapshot
        val elapsed = before?.let { reading.elapsedMs - it.elapsedMs }
        val cpuDelta = delta(before?.processCpuMs, reading.processCpuMs)
        val cpu = if (elapsed != null && cpuDelta != null) cpuDelta * 100.0 / elapsed else null
        val rx = delta(before?.uidRxBytes, reading.uidRxBytes)
        val tx = delta(before?.uidTxBytes, reading.uidTxBytes)
        snapshot = ResourceMonitorSnapshot(
            processCpuPercent = cpu,
            processPssBytes = valid(reading.processPssBytes),
            processRssBytes = valid(reading.processRssBytes),
            uidRxBytesPerSecond = if (elapsed != null && rx != null) rx * 1000.0 / elapsed else null,
            uidTxBytesPerSecond = if (elapsed != null && tx != null) tx * 1000.0 / elapsed else null,
            recording = snapshot.recording,
            processMemory = reading.processMemory.let { ResourceMonitorMemory(
                valid(it.javaHeapBytes), valid(it.nativeHeapBytes), valid(it.graphicsBytes),
                valid(it.codeBytes), valid(it.stackBytes), valid(it.privateOtherBytes), valid(it.systemBytes),
            ) },
            processThreads = valid(reading.processThreads),
            uidRxBytes = valid(reading.uidRxBytes),
            uidTxBytes = valid(reading.uidTxBytes),
            mcrfSessionPresent = reading.mcrfSessionPresent,
            mcrfConnected = reading.mcrfConnected,
        )
        if (snapshot.recording.active) {
            val session = snapshot.recording
            val prior = recordingPrevious
            val duration = prior?.let { reading.elapsedMs - it.elapsedMs } ?: 0L
            val recordedRx = delta(prior?.uidRxBytes, reading.uidRxBytes)
            val recordedTx = delta(prior?.uidTxBytes, reading.uidTxBytes)
            val recordedCpu = delta(prior?.processCpuMs, reading.processCpuMs)
            if (recordedCpu != null && duration > 0) {
                cpuSumMs += recordedCpu.toDouble()
                cpuWeightMs += duration.toDouble()
            }
            val sessionCpu = if (recordedCpu != null && duration > 0) recordedCpu * 100.0 / duration else null
            snapshot = snapshot.copy(recording = session.copy(
                samples = add(session.samples, 1),
                observedMs = add(session.observedMs, duration),
                rxBytes = accumulate(session.rxBytes, recordedRx, reading.uidRxBytes),
                txBytes = accumulate(session.txBytes, recordedTx, reading.uidTxBytes),
                rxPartial = session.rxPartial || valid(reading.uidRxBytes) == null || (prior != null && recordedRx == null),
                txPartial = session.txPartial || valid(reading.uidTxBytes) == null || (prior != null && recordedTx == null),
                averageProcessCpuPercent = if (cpuWeightMs > 0) cpuSumMs * 100.0 / cpuWeightMs else null,
                peakProcessCpuPercent = maximum(session.peakProcessCpuPercent, sessionCpu),
                peakProcessPssBytes = maximum(session.peakProcessPssBytes, valid(reading.processPssBytes)),
                // Only the first sample is the start; a later available PSS must not rewrite it.
                startProcessPssBytes = if (session.samples == 0L) valid(reading.processPssBytes) else session.startProcessPssBytes,
                currentProcessPssBytes = valid(reading.processPssBytes),
                peakProcessRssBytes = maximum(session.peakProcessRssBytes, valid(reading.processRssBytes)),
            ))
            recordingPrevious = reading
        }
        previous = reading
        return snapshot
    }

    private fun valid(value: Long?) = value?.takeIf { it >= 0 }
    private fun delta(before: Long?, now: Long?): Long? =
        if (before != null && now != null && before >= 0 && now >= before) now - before else null
    private fun accumulate(total: Long?, change: Long?, current: Long?): Long? = when {
        change != null -> add(total ?: 0, change)
        total != null -> total
        valid(current) != null -> 0L
        else -> null
    }
    private fun add(a: Long, b: Long) = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    private fun <T : Comparable<T>> maximum(a: T?, b: T?): T? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}
