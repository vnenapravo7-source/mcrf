package org.mcrf.client

/** Complete ID coverage and exactly one ordered progress event per requested server. */
internal class ClientPrimaryProbeResults(ids: List<String>, private val progress: (Int, String, Long) -> Unit) {
    private val values = ids.associateWith { -1L }.toMutableMap()
    private val completed = HashSet<String>()

    init {
        require(ids.size in 1..256 && ids.distinct().size == ids.size &&
            ids.all { it.matches(Regex("[0-9a-f]{64}")) }) { "PROBE_SERVER_IDS" }
    }

    @Synchronized fun record(id: String, delay: Long) {
        require(values.containsKey(id)) { "PROBE_UNKNOWN_ID" }
        if (!completed.add(id)) return
        val value = if (delay >= 0) delay else -1L
        values[id] = value
        // A disconnected receiver must never prevent native cleanup or remaining coverage.
        try { progress(completed.size, id, value) } catch (_: Exception) { }
    }

    @Synchronized fun finish(): Map<String, Long> {
        values.keys.toList().forEach { if (it !in completed) record(it, -1) }
        return values.toMap()
    }
}
