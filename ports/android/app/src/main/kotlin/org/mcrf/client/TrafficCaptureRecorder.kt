package org.mcrf.client

import java.util.UUID

/** Session gate shared by both transports. Queue callers must carry the original token.
 * Stop/delete/expiry fence queued events, not only new callbacks. */
class TrafficCaptureRecorder(private val store: TrafficJournalStore,
                             private val elapsed: () -> Long, private val wall: () -> Long) {
    private var options: TrafficCapturePolicy.Options? = null
    private var token: String? = null
    private var deadline: Long? = null
    @Synchronized fun start(value: TrafficCapturePolicy.Options): String {
        check(token == null)
        store.setEnabled(false) // retire legacy persisted opt-in; only this live session may ingest
        store.clearDnsCorrelations()
        store.setEnabled(true)
        options = value
        deadline = TrafficCapturePolicy.deadline(elapsed(), value.seconds)
        return UUID.randomUUID().toString().also { token = it }
    }
    @Synchronized fun current(): String? = token
    @Synchronized fun remaining(): Long? = deadline?.let { (it - elapsed()).coerceAtLeast(0) }
    @Synchronized fun expire(): Boolean {
        if (token == null || !TrafficCapturePolicy.expired(elapsed(), deadline)) return false
        stop(); return true
    }
    @Synchronized fun stop() {
        token = null; options = null; deadline = null // fence before storage I/O, even if it fails
        store.setEnabled(false); store.clearDnsCorrelations()
    }
    @Synchronized fun delete() {
        token = null; options = null; deadline = null
        store.delete()
    }
    @Synchronized fun record(captured: String, packages: Collection<String>?, event: TrafficJournalStore.Observation): Boolean {
        if (captured != token || expire() || options?.accepts(packages) != true) return false
        // Queue latency must not move a connection past a later DNS answer or
        // extend the retention period of metadata already observed by the hook.
        return store.ingest(event.copy(session = captured))
    }
    @Synchronized fun dns(captured: String, ip: String, domain: String, uid: Int, packages: Collection<String>?, ttl: Long) {
        if (captured != token || expire() || options?.accepts(packages) != true) return
        store.correlateDns(ip, domain, uid, wall(), ttl)
    }
}
