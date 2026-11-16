package org.mcrf.client

/** Process-wide ownership survives stopService/onDestroy followed by a fresh service instance. */
internal class ClientPrimaryProbeLifecycle {
    internal class Run(val cancel: () -> Unit)
    private val lock = Any()
    private var active: Run? = null
    private var cleanupFailed = false
    private var lastResults: Map<String, Long> = emptyMap()
    private val waiters = ArrayList<(Boolean, Map<String, Long>) -> Unit>()

    fun begin(cancel: () -> Unit): Run? = synchronized(lock) {
        if (active != null || cleanupFailed) null else Run(cancel).also { active = it; lastResults = emptyMap() }
    }

    fun quiesce(reply: (Boolean, Map<String, Long>) -> Unit) {
        val run: Run?
        val snapshot: Pair<Boolean, Map<String, Long>>
        synchronized(lock) {
            run = active
            snapshot = !cleanupFailed to lastResults
            if (run != null) waiters.add(reply)
        }
        if (run != null) { try { run.cancel() } catch (_: Exception) { } }
        else try { reply(snapshot.first, snapshot.second) } catch (_: Exception) { }
    }

    fun finish(run: Run, cleaned: Boolean, results: Map<String, Long>) {
        val replies: List<(Boolean, Map<String, Long>) -> Unit>
        val snapshot = results.toMap()
        synchronized(lock) {
            check(active === run) { "PROBE_RUN_OWNER" }
            cleanupFailed = !cleaned
            lastResults = snapshot
            active = null
            replies = waiters.toList()
            waiters.clear()
        }
        replies.forEach { try { it(cleaned, snapshot) } catch (_: Exception) { } }
    }
}
