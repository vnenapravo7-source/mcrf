package org.mcrf.client

/** Small, single-stream application benchmark, not a maximum line-capacity estimate or passive UID meter.
 * Protocol: https://github.com/cloudflare/speedtest/blob/main/README.md (GET __down, POST __up).
 */
internal object SpeedTestPlan {
    const val HOST = "speed.cloudflare.com"
    const val DOWNLOAD_BYTES = 8 * 1024 * 1024
    const val UPLOAD_BYTES = 2 * 1024 * 1024
    const val CONTROL_BYTES = 4096
    const val LATENCY_COUNT = 3
    const val RUN_MS = 45_000L
    const val REQUEST_MS = 15_000L
    fun mbps(bytes: Long, nanos: Long): Double {
        require(bytes > 0 && nanos > 0)
        return bytes.toDouble() * 8_000.0 / nanos.toDouble()
    }
}

internal enum class SpeedTestPath { APP_NETWORK, CURRENT_MCRF_PROXY, SAVED_SERVER }
internal enum class SpeedTestStage { LATENCY, DOWNLOAD, UPLOAD }
internal data class SpeedTestSample(val bytes: Long, val elapsedNs: Long)
internal data class SpeedTestProgress(val stage: SpeedTestStage, val bytes: Long, val limit: Long, val sample: Int = 0)
internal data class SpeedTestResult(val httpResponseMs: Double, val downloadMbps: Double, val uploadMbps: Double)

/** No credentials, endpoint bodies or server/profile identities in progress/results. */
internal interface SpeedTestTransport : AutoCloseable {
    fun checkRoute()
    fun exchange(stage: SpeedTestStage, bytes: Int, token: DiagnosticCancellation, progress: (Long) -> Unit): SpeedTestSample
}
