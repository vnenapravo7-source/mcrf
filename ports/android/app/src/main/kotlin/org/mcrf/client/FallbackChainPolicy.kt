package org.mcrf.client

/** A user-defined order, never a latency ranking. Budgets survive brief successful reconnects. */
class FallbackChainPolicy {
    private var identity = ""
    private val tried = linkedSetOf<String>()
    private var switches = 0
    private var nextAt = 0L
    private var startedAt = 0L

    @Synchronized fun reset() { identity = ""; tried.clear(); switches = 0; nextAt = 0; startedAt = 0 }
    @Synchronized fun exhausted(now: Long) = identity.isNotEmpty() &&
        (switches >= MAX_ATTEMPTS || now - startedAt !in 0..MAX_EPISODE_MS)

    @Synchronized fun next(scope: String, ids: List<String>, current: String, manual: String, network: Long,
             confirmedFailure: Boolean, internet: Boolean, automatic: Boolean, now: Long): String? {
        if (!confirmedFailure || !internet || automatic || network == 0L || current !in ids || ids.size < 2) return null
        // Physical flapping retires health evidence, but must not renew the attempt budget.
        val key = "$scope:$manual:${ids.joinToString(",")}"
        if (identity != key) {
            reset(); identity = key; startedAt = now; tried.add(current)
        }
        if (now < nextAt || switches >= MAX_ATTEMPTS || now - startedAt !in 0..MAX_EPISODE_MS) return null
        val index = ids.indexOf(current)
        val next = ids.drop(index + 1).firstOrNull { it !in tried } ?: return null
        tried.add(next); switches++; nextAt = now + COOLDOWN_MS
        return next
    }

    companion object {
        const val MAX_ENTRIES = 32
        const val MAX_ATTEMPTS = 3
        const val COOLDOWN_MS = 15_000L
        const val MAX_EPISODE_MS = 180_000L
        fun valid(ids: List<String>) = ids.size <= MAX_ENTRIES && ids.distinct().size == ids.size &&
            ids.all { it.matches(Regex("[0-9a-f]{64}")) }
        fun confirmedConnectionFailure(error: Throwable): Boolean {
            if (RecoveryPolicy.classify(error) != RecoveryReason.SERVER_FAILURE) return false
            var cause: Throwable? = error
            repeat(16) {
                val value = cause ?: return false
                if (value is java.net.ConnectException || value is java.net.SocketTimeoutException) return true
                cause = value.cause
            }
            return false
        }
    }
}
