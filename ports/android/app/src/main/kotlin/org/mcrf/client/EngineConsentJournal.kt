package org.mcrf.client

/** Durable intent witness, never inferred from focus, elapsed time or UI phase.
 * LAUNCHING is committed BEFORE calling Android; a crash in that gap is unknown. */
internal object EngineConsentJournal {
    enum class Stage { PREPARED, LAUNCHING }
    enum class Recovery { LIVE_PREPARATION, RELEASE_PREPARED, RETAIN_OUTSTANDING, RETAIN_UNKNOWN }
    data class Entry(val token: String, val stage: Stage) {
        init { require(token.matches(TOKEN)) }
        fun encode() = "$token|${stage.name}"
    }
    private val TOKEN = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    fun parse(raw: String?): Entry? {
        if (raw == null || raw.length !in 45..46) return null
        val parts = raw.split('|')
        if (parts.size != 2 || !parts[0].matches(TOKEN)) return null
        val stage = Stage.values().firstOrNull { it.name == parts[1] } ?: return null
        return Entry(parts[0], stage)
    }
    fun recovery(token: String, entry: Entry?, liveToken: String?): Recovery = when {
        entry == null || entry.token != token -> Recovery.RETAIN_UNKNOWN
        entry.stage == Stage.LAUNCHING -> Recovery.RETAIN_OUTSTANDING
        liveToken == token -> Recovery.LIVE_PREPARATION
        else -> Recovery.RELEASE_PREPARED
    }
    fun mayAbort(token: String, entry: Entry?) = entry != null && entry.token == token && entry.stage == Stage.PREPARED
}
