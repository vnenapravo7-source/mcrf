package org.mcrf.client

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Pure orchestration. A watchdog closes attached sockets even while DNS/connect/write/read blocks.
 * No retries, parallel transfers or resume. SpeedTestService owns UI-independent
 * lifetime/result state and the deadline that also includes route preparation.
 */
internal class SpeedTestRunner(private val transport: SpeedTestTransport) {
    fun run(parent: DiagnosticCancellation, progress: (SpeedTestProgress) -> Unit): SpeedTestResult {
        val token = DiagnosticCancellation(SpeedTestPlan.RUN_MS)
        val lease = try { parent.attach(AutoCloseable { token.cancel() }) }
            catch (error: Exception) { transport.close(); throw error }
        try { return runBounded(parent, token, progress) }
        finally { lease.close(); token.cancel() }
    }
    private fun runBounded(parent: DiagnosticCancellation, token: DiagnosticCancellation,
                           progress: (SpeedTestProgress) -> Unit): SpeedTestResult {
        val active = AtomicReference<DiagnosticCancellation?>()
        val failure = AtomicReference<Exception?>()
        val clock = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mcrf-speedtest-deadline").apply { isDaemon = true } }
        val monitor = clock.scheduleAtFixedRate({
            try { parent.check(); token.check(); active.get()?.check(); transport.checkRoute() }
            catch (error: Exception) { failure.compareAndSet(null, error); token.cancel() }
        }, 0, 100, TimeUnit.MILLISECONDS)
        fun once(stage: SpeedTestStage, bytes: Int, index: Int = 0): SpeedTestSample {
            parent.check(); token.check(); transport.checkRoute()
            val child = DiagnosticCancellation(SpeedTestPlan.REQUEST_MS)
            val lease = token.attach(AutoCloseable { child.cancel() })
            active.set(child)
            var last = 0L
            progress(SpeedTestProgress(stage, 0, bytes.toLong(), index))
            try {
                val result = transport.exchange(stage, bytes, child) { transferred ->
                    require(transferred in 0..bytes.toLong())
                    val now = System.nanoTime()
                    if (now - last >= 200_000_000 || transferred == bytes.toLong()) {
                        last = now; progress(SpeedTestProgress(stage, transferred, bytes.toLong(), index))
                    }
                }
                child.check(); token.check(); transport.checkRoute()
                require(result.bytes == bytes.toLong() && result.elapsedNs > 0)
                return result
            } finally { active.compareAndSet(child, null); child.cancel(); lease.close() }
        }
        try {
            val latency = (1..SpeedTestPlan.LATENCY_COUNT).map { once(SpeedTestStage.LATENCY, 0, it).elapsedNs }.sorted()[1]
            val download = once(SpeedTestStage.DOWNLOAD, SpeedTestPlan.DOWNLOAD_BYTES)
            val upload = once(SpeedTestStage.UPLOAD, SpeedTestPlan.UPLOAD_BYTES)
            token.check(); transport.checkRoute()
            return SpeedTestResult(latency / 1_000_000.0, SpeedTestPlan.mbps(download.bytes, download.elapsedNs),
                SpeedTestPlan.mbps(upload.bytes, upload.elapsedNs))
        } catch (error: Exception) { throw (failure.get() ?: error) }
        finally { monitor.cancel(false); clock.shutdownNow(); token.cancel(); transport.close() }
    }
}
