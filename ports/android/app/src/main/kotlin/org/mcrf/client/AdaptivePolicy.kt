package org.mcrf.client

object AdaptivePolicy {
    data class Environment(val lowBattery: Boolean = false, val mobile: Boolean = false, val roaming: Boolean = false)
    data class Budget(val probeIntervalMs: Long, val updateIntervalMs: Long)
    fun budget(settings: ClientBehaviorSettings, environment: Environment, baseProbeMs: Long, baseUpdateMs: Long): Budget {
        val constrained = settings.adaptivePolicyEnabled &&
            (settings.conserveBattery && environment.lowBattery || settings.conserveMobile && environment.mobile || settings.conserveRoaming && environment.roaming)
        return Budget(if (constrained) maxOf(baseProbeMs, 300_000) else baseProbeMs,
            if (constrained) maxOf(baseUpdateMs, 24 * 3_600_000L) else baseUpdateMs)
    }
    /** No stop/disconnect decision exists in this policy. Manual pin always wins. */
    class SwitchGuard(private val cooldown: Long = 120_000L) {
        private var failures = 0
        private var observed = ""
        private var previous = Long.MIN_VALUE
        private val visited = linkedMapOf<String, Long>()
        @Synchronized fun observe(server: String, healthy: Boolean): Int {
            if (server != observed) { observed = server; failures = 0 }
            failures = if (healthy) 0 else (failures + 1).coerceAtMost(100)
            return failures
        }
        @Synchronized fun mayReconsider(settings: ClientBehaviorSettings, healthy: Boolean): Boolean =
            if (healthy) true else failures >= if(settings.unstableNetwork)settings.unstableFailures else 1
        @Synchronized fun admit(current: String, target: String, now: Long, manualPin: Boolean): Boolean {
            if (manualPin || current == target || target.isBlank()) return false
            if (previous != Long.MIN_VALUE && (now < previous || now - previous < cooldown)) return false
            visited.entries.removeAll { now >= it.value && now - it.value >= 30 * 60_000 }
            if (target in visited) return false
            visited[current] = now; visited[target] = now; previous = now
            while (visited.size > 128) visited.remove(visited.keys.first())
            return true // Reserve before attempting; a failed attempt cannot spin through candidates.
        }
    }
}
