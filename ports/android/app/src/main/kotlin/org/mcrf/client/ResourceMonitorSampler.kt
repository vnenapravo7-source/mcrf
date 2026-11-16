package org.mcrf.client

import android.net.TrafficStats
import android.os.Debug
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.FileInputStream
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Aggregate numeric observations only. Implementations must be local, bounded and non-networking. */
internal fun interface ResourceMonitorSource {
    fun read(): ResourceMonitorReading
}

internal class ResourceMonitorAndroidSource : ResourceMonitorSource {
    companion object {
        // Mode switches and Activity recreation cannot bypass the Android read budget.
        internal val readGate = ResourceMonitorReadGate()
    }

    override fun read(): ResourceMonitorReading {
        check(Looper.myLooper() != Looper.getMainLooper())
        val cpu = available { Process.getElapsedCpuTime() }
        val memory = try { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) } } catch (_: Exception) { null }
        val pss = memory?.totalPss?.toLong()?.takeIf { it > 0 }?.times(1024L)
        val status = try {
            // Fixed read budget: never load smaps or arbitrary process paths into memory.
            val buffer = ByteArray(16 * 1024)
            val count = FileInputStream("/proc/self/status").use { it.read(buffer) }
            if (count > 0) String(buffer, 0, count, Charsets.US_ASCII) else ""
        } catch (_: Exception) { "" }
        val uid = Process.myUid()
        val rx = available { TrafficStats.getUidRxBytes(uid) }
        val tx = available { TrafficStats.getUidTxBytes(uid) }
        fun summary(key: String): Long? = try {
            ResourceMonitorProc.kilobytes(memory?.getMemoryStat("summary.$key"))
        } catch (_: Exception) { null }
        return ResourceMonitorReading(
            SystemClock.elapsedRealtime(), cpu, pss, ResourceMonitorProc.rssBytes(status), rx, tx,
            ResourceMonitorMemory(summary("java-heap"), summary("native-heap"), summary("graphics"),
                summary("code"), summary("stack"), summary("private-other"), summary("system")),
            ResourceMonitorProc.threads(status), McrfVpnService.hasActiveSession(),
            ConnectionPresentation.connected(McrfVpnService.status),
        )
    }

    private inline fun available(read: () -> Long): Long? = try {
        read().takeIf { it >= 0 }
    } catch (_: Exception) {
        // No raw errors, process names, hosts or payloads are retained/logged.
        null
    }
}

internal object ResourceMonitorProc {
    fun kilobytes(value: String?): Long? = value?.toLongOrNull()
        ?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1024 }?.times(1024L)

    fun threads(status: String): Long? = status.lineSequence().firstOrNull { it.startsWith("Threads:") }
        ?.substringAfter(':')?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    fun rssBytes(status: String): Long? {
        val row = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
        val parts = row.substringAfter(':').trim().split(Regex("\\s+"))
        if (parts.size != 2 || parts[1] != "kB") return null
        return kilobytes(parts[0])
    }
}

/**
 * One lazy worker per controller, one read per >=1000ms, no catch-up reads or command queue.
 * Cancellation invalidates in-flight results; commands never wait for proc/Debug/TrafficStats I/O.
 * A single conflated UI notification is supplied by the controller.
 */
internal class ResourceMonitorSampler(
    private val source: ResourceMonitorSource = ResourceMonitorAndroidSource(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val changed: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val model = ResourceMonitorModel()
    private var executor: ScheduledThreadPoolExecutor? = null
    private var future: ScheduledFuture<*>? = null
    private var revision = 0L
    private var recordingRevision = 0L
    private val readGate = if (source is ResourceMonitorAndroidSource) ResourceMonitorAndroidSource.readGate else ResourceMonitorReadGate()
    private var running = false
    private var closed = false

    fun snapshot(): ResourceMonitorSnapshot = synchronized(lock) { model.snapshot }

    fun start() = synchronized(lock) {
        if (closed || running) return@synchronized
        running = true
        val token = ++revision
        val worker = executor ?: ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "resource-monitor").apply { isDaemon = true }
        }.also { it.removeOnCancelPolicy = true; executor = it }
        future = worker.scheduleWithFixedDelay({ sample(token) }, 0, 1000, TimeUnit.MILLISECONDS)
    }

    private fun sample(token: Long) {
        val recordingToken = synchronized(lock) {
            if (!running || closed || revision != token) return
            if (!readGate.admit(clock())) return
            recordingRevision
        }
        val reading = try { source.read() } catch (_: Exception) { ResourceMonitorReading(clock()) }
        val accepted = synchronized(lock) {
            // A command issued during an OS read owns the new recording boundary.
            // Never attribute an older in-flight observation to that new session.
            if (!running || closed || revision != token || recordingRevision != recordingToken) false
            else { model.accept(reading); true }
        }
        if (accepted) changed()
    }

    fun startRecording() = synchronized(lock) {
        if (running && !closed && !model.snapshot.recording.active) {
            recordingRevision++
            model.startRecording()
        }
    }
    fun stopRecording() = synchronized(lock) { recordingRevision++; model.stopRecording() }
    fun resetRecording() = synchronized(lock) { recordingRevision++; model.resetRecording() }

    fun stop() = synchronized(lock) {
        running = false
        revision++
        future?.cancel(false)
        future = null
        model.suspendSampling()
    }

    override fun close() = synchronized(lock) {
        stop()
        closed = true
        executor?.shutdownNow()
        executor = null
    }
}
