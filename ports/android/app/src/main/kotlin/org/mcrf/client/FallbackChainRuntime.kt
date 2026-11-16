package org.mcrf.client

/** Runtime-only evidence. Never persists selection, probe failures or a renewed retry budget. */
class FallbackChainRuntime {
    val policy = FallbackChainPolicy()
    private var nextHealthAt = 0L
    private var evidence = ""
    private var failures = 0
    @Synchronized fun reset() { policy.reset(); nextHealthAt = 0; evidence = ""; failures = 0 }
    @Synchronized fun due(now: Long): Boolean {
        if (now < nextHealthAt) return false
        nextHealthAt = now + FallbackChainPolicy.COOLDOWN_MS
        return true
    }
    @Synchronized fun clearEvidence() { evidence = ""; failures = 0 }
    @Synchronized fun report(server: String, network: Long, healthy: Boolean): Boolean {
        val key = "$server:$network"
        if (evidence != key) { clearEvidence(); evidence = key }
        failures = if (healthy) 0 else failures + 1
        return failures >= 2
    }
}
