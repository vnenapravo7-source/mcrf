package org.mcrf.client

internal data class SpeedTestRequest(val providerId: String, val path: SpeedTestPath, val serverId: String? = null)
internal enum class SpeedTestPhase { IDLE, PREPARING, RUNNING, STOPPING, COMPLETE, FAILED, CANCELLED }
internal data class SpeedTestSnapshot(
    val runId: Long = 0, val request: SpeedTestRequest? = null, val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    val label: String = "", val message: String = "Не запущен", val progress: SpeedTestProgress? = null,
    val result: SpeedTestResult? = null
) {
    val busy get() = phase in setOf(SpeedTestPhase.PREPARING, SpeedTestPhase.RUNNING, SpeedTestPhase.STOPPING)
}

/** Pure run ownership. Stale notification actions/callbacks cannot affect a later run. */
internal class SpeedTestState {
    @Volatile var snapshot = SpeedTestSnapshot()
        private set
    private var sequence = java.security.SecureRandom().nextLong().ushr(2)
    @Synchronized fun begin(request: SpeedTestRequest): SpeedTestSnapshot? {
        if (snapshot.busy) return null
        check(SpeedTestProviders.find(request.providerId)?.available == true)
        check((request.path == SpeedTestPath.SAVED_SERVER) == (request.serverId != null))
        snapshot = SpeedTestSnapshot(++sequence, request, SpeedTestPhase.PREPARING, message = "Подготовка маршрута…")
        return snapshot
    }
    @Synchronized fun progress(id: Long, label: String, progress: SpeedTestProgress? = null) {
        if (snapshot.runId != id || !snapshot.busy || snapshot.phase == SpeedTestPhase.STOPPING) return
        snapshot = snapshot.copy(phase = SpeedTestPhase.RUNNING, label = label, progress = progress)
    }
    @Synchronized fun stop(id: Long, message: String): Boolean {
        if (snapshot.runId != id || !snapshot.busy) return false
        if (snapshot.phase == SpeedTestPhase.STOPPING) return false
        snapshot = snapshot.copy(phase = SpeedTestPhase.STOPPING, message = message, result = null)
        return true
    }
    @Synchronized fun finish(id: Long, result: SpeedTestResult?, problem: String) {
        if (snapshot.runId != id || !snapshot.busy) return
        val stopping = snapshot.phase == SpeedTestPhase.STOPPING
        snapshot = snapshot.copy(phase = when {
            stopping -> SpeedTestPhase.CANCELLED
            result != null -> SpeedTestPhase.COMPLETE
            else -> SpeedTestPhase.FAILED
        }, result = if (stopping) null else result,
            message = if (stopping) snapshot.message else if (result != null) "Готово" else problem)
    }
}
