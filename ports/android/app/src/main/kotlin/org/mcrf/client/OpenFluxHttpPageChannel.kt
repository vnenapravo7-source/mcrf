package org.mcrf.client

import java.util.ArrayDeque
import java.util.Base64
import org.json.JSONArray
import org.json.JSONTokener

/** Bounded page-message request queue, NOT a WebMessagePort/JS/UI entry yet.
 * Message shape: ["request", id, method, absoluteSyntheticUrl, headerPairs, bodyB64].
 * No opcode can OPEN, Send verification, change foreground or cancel another
 * owner. A future exact-origin port must gate this by its exact visible page.
 * Owner checks must be pure/nonblocking (atomics/clock), never UI/IPC. */
internal class OpenFluxHttpPageChannel(
    private val policy: OpenFluxHttpPagePolicy,
    private val window: OpenFluxWindowAdmission,
    private val epoch: OpenFluxWindowAdmission.Permit,
    private val ownerCurrent: () -> Boolean
) : AutoCloseable {
    class Request internal constructor(val id: String, val method: String, val target: String,
        val headers: List<Pair<String, String>>, val body: ByteArray, internal val reserved: Int) : AutoCloseable {
        override fun close() { body.fill(0) }
        override fun toString() = "OpenFluxHttpPageRequest(redacted)"
    }
    private val queue = ArrayDeque<Request>()
    private val used = mutableSetOf<String>()
    private var active: Request? = null
    private var reserved = 0
    private var closed = false
    override fun toString() = "OpenFluxHttpPageChannel(redacted)"
    private fun live() = !closed && window.current(epoch) && try { ownerCurrent() } catch (_: Exception) { false }
    private fun checkLive(): Boolean { if (live()) return true; close(); return false }
    /** Shape parsing is intentionally not advertised as strict JSON grammar:
     * Android JSONTokener is lenient. Arrays avoid duplicate-object-field
     * interpretation; native HTTP request grammar remains authoritative. */
    @Synchronized fun accept(message: String): Boolean {
        if (!checkLive() || queue.size + (if (active == null) 0 else 1) >= 8 || used.size >= MAX_REQUESTS ||
            OpenFluxHttpIpc.utf8Size(message, 114688) == null || !OpenFluxJsonNesting.bounded(message)) return false
        val root = try {
            val reader = JSONTokener(message); val value = reader.nextValue() as? JSONArray ?: return false
            if (reader.nextClean() != '\u0000') return false
            value
        } catch (_: Exception) { return false }
        if (root.length() != 6 || root.opt(0) != "request") return false
        val id = (root.opt(1) as? String)?.takeIf { it.matches(ID) && it !in used } ?: return false
        val method = root.opt(2) as? String ?: return false
        val url = root.opt(3) as? String ?: return false
        val target = policy.requestTarget(url, method) ?: return false
        val headerArray = root.opt(4) as? JSONArray ?: return false
        val headers = OpenFluxHttpIpc.headers(headerArray.toString()) ?: return false
        val encoded = root.opt(5) as? String ?: return false
        if (encoded.length > 87384 || encoded.length % 4 != 0) return false
        val body = try { Base64.getDecoder().decode(encoded) } catch (_: Exception) { return false }
        var transferred = false
        try {
            if (body.size > 65536 || Base64.getEncoder().encodeToString(body) != encoded ||
                method in setOf("GET", "HEAD") && body.isNotEmpty()) return false
            val size = body.size + (OpenFluxHttpIpc.utf8Size(url, 8192) ?: return false) +
                headers.sumOf { (OpenFluxHttpIpc.utf8Size(it.first, 8192) ?: return false) +
                    (OpenFluxHttpIpc.utf8Size(it.second, 8192) ?: return false) + 4 }
            if (reserved + size > 131072 || !checkLive()) return false
            val request = Request(id, method, target, headers.toList(), body, size)
            used.add(id); reserved += size; queue.add(request); transferred = true
            return true
        } finally { if (!transferred) body.fill(0) }
    }
    /** One exact active identity until local completion. Caller rechecks current
     * before native admission, closes every response and never replays an
     * uncertain native operation. Already copied/admitted bodies not revocable. */
    @Synchronized fun next(): Request? {
        if (!checkLive() || active != null) return null
        return queue.poll()?.also { active = it }
    }
    @Synchronized fun current(request: Request): Boolean = checkLive() && active === request
    @Synchronized fun complete(request: Request): Boolean {
        if (!checkLive() || active !== request) return false
        request.close(); reserved -= request.reserved; active = null
        return true // Local retirement, not native apply, browser render or consumer ACK.
    }
    @Synchronized fun empty(): Boolean = checkLive() && active == null && queue.isEmpty()
    @Synchronized override fun close() {
        closed = true; active?.close(); active = null; queue.forEach { it.close() }; queue.clear(); reserved = 0
    }
    companion object {
        private val ID = Regex("[0-9a-f]{32}")
        // Native/session resource nonce budget60 already includes OPEN and the
        // initial navigation GET. Leave separate final reply/cancel reservations.
        const val MAX_REQUESTS = 58
    }
}
