package org.mcrf.client

/** Explicit consent; never inferred from the ordinary/manual probe preferences. */
data class HealthSettings(val enabled: Boolean = false) {
    companion object {
        const val INTERVAL_MS = 15 * 60_000L
        const val TIMEOUT_MS = 2_000
        const val ATTEMPTS = 3
        // Conservative accounting for TCP setup/retransmission; no application payload.
        // OS/DNS overhead is an estimate, not an on-wire byte counter.
        const val ATTEMPT_BYTES = 8_192L
        const val DAILY_BYTES = 262_144L
        const val DAY_MS = 24 * 60 * 60_000L
        const val RETENTION_MS = 7 * DAY_MS
        const val FRESH_MS = 60 * 60_000L
        const val MAX_SERVERS = 64
        const val MAX_SAMPLES = 256
        const val MAX_STORAGE_BYTES = 65_536
    }
}

enum class HealthDimension { SERVER, NETWORK, ENGINE }
enum class HealthNetwork { UNKNOWN, WIFI, CELLULAR, OTHER }
enum class HealthEngine { UNKNOWN, STEER, SINGBOX, OTHER }
enum class HealthDisconnect { UNKNOWN, USER, NETWORK_LOST, TIMEOUT, ENGINE_STOPPED, ERROR }
enum class HealthEventKind { SESSION_STARTED, CONNECTED, DISCONNECTED, ERROR, RECOVERED }

/** Lifecycle callers supply a positive, increasing session generation, never an error/log string.
 * STARTED must precede the other events. Duplicate/retired session events are ignored.
 * The VPN service emits these without error strings, credentials or payload. */
data class HealthEvent(
    val session: Long,
    val kind: HealthEventKind,
    val dimension: HealthDimension = HealthDimension.ENGINE,
    val network: HealthNetwork = HealthNetwork.UNKNOWN,
    val engine: HealthEngine = HealthEngine.UNKNOWN,
    val reason: HealthDisconnect = HealthDisconnect.UNKNOWN
)

data class HealthSample(
    val at: Long,
    val pingMs: Long?,
    val attempts: Int,
    val failures: Int,
    val network: HealthNetwork
)

data class HealthSnapshot(
    val samples: List<HealthSample> = emptyList(),
    val sessions: Long = 0,
    val uptimeMs: Long = 0,
    val successes: List<Long> = List(3) { 0L },
    val errors: List<Long> = List(3) { 0L },
    val lastDisconnect: HealthDisconnect? = null,
    val network: HealthNetwork = HealthNetwork.UNKNOWN,
    val engine: HealthEngine = HealthEngine.UNKNOWN,
    val lastEventAt: Long? = null
)
