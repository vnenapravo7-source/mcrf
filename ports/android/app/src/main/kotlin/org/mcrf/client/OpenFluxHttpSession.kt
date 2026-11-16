package org.mcrf.client

import java.net.URI
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID
import org.json.JSONArray

/** Source-only protocol model. No Binder/browser/dispatcher entry is enabled.
 * Ownership checks MUST be pure, nonblocking checks. No callbacks perform IO
 * under this monitor. The future UI must supply real manual/renderer receipts. */
internal class OpenFluxHttpSession(
    private val child: String, private val ticket: String, private val deadline: Long,
    private val childAlive: () -> Boolean, private val window: OpenFluxWindowAdmission,
    private val now: () -> Long = System::nanoTime,
    private val nonce: () -> String = { UUID.randomUUID().toString().replace("-", "") }
) : AutoCloseable {
    enum class Received { NOT_HTTP, CONSUMED, COMPLETE, REJECTED }
    class Status(val code: Int, val address: String? = null) { override fun toString() = "OpenFluxHttpStatus(redacted)" }
    class Response(val request: String, val status: Int, val headers: List<OpenFluxHttpWire.Header>, val body: ByteArray) : AutoCloseable {
        override fun close() { body.fill(0) }
        override fun toString() = "OpenFluxHttpResponse(redacted)"
    }
    private class Job(val id: String, val method: String, val target: String, val headers: List<Pair<String, String>>,
                      val body: ByteArray, val reserved: Int) { fun clear() { body.fill(0) } }
    private val queue = ArrayDeque<Job>()
    private val usedIds = mutableSetOf<String>()
    private var closed = false
    private var openNonce: String? = null
    private var foreground: OpenFluxWindowAdmission.Permit? = null
    private var permit: String? = null
    private var address: String? = null
    private var origin: String? = null
    private var initialTransferred = false
    private var replyNonce: String? = null
    private var replyAccepted = false
    private var active: Job? = null
    private var reserved = 0
    private var metadata: OpenFluxHttpWire.Metadata? = null
    private var bytes: ByteArray? = null
    private var received = 0
    private var complete = false
    private var cancellation: String? = null
    private var cancelledPermit: String? = null
    init {
        require(child.matches(ID) && ticket.matches(ID))
        val remaining = try { Math.subtractExact(deadline, now()) } catch (_: ArithmeticException) { 0L }
        require(remaining in 1..150_000_000_000L)
    }
    override fun toString() = "OpenFluxHttpSession(redacted)"
    private fun live(): Boolean = !closed && now() < deadline && window.current(foreground) &&
        try { childAlive() } catch (_: Exception) { false }
    private fun checkLive(): Boolean { if (live()) return true; close(); return false }
    @Synchronized fun status(): Status {
        if (closed) return Status(-1)
        if (openNonce == null) return Status(0)
        if (!checkLive()) return Status(-1)
        return if (permit == null) Status(1) else Status(2, address)
    }
    private fun freshId(forReply: Boolean = false): String? {
        if (usedIds.size >= if (forReply) 61 else 60) return null // Reserve a distinct final reply nonce and cancel budget.
        val value = try { nonce() } catch (_: Exception) { return null }
        if (!value.matches(ID) || !usedIds.add(value)) return null
        return value
    }
    @Synchronized fun openAfterUserAction(action: OpenFluxWindowAdmission.Permit?): String? {
        if (closed || openNonce != null || !window.current(action) || now() >= deadline ||
            !try { childAlive() } catch (_: Exception) { false }) return null
        val id = freshId() ?: return null
        foreground = action; openNonce = id
        return "OPENFLUX_HTTP_OPEN|$ticket|$id"
    }
    private fun cancel(nativePermit: String) {
        if (cancelledPermit == nativePermit) return
        cancelledPermit = nativePermit; cancellation = "OPENFLUX_HTTP_CLOSE|$ticket|$nativePermit"
    }
    /** Caller must deliver this through the exact child; no cancellation IO here. */
    @Synchronized fun takeCancelCommand(): String? { val value = cancellation; cancellation = null; return value }
    private fun utf8Size(value: String, limit: Int): Int? {
        if (value.length > limit) return null
        val encoded = try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)) } catch (_: Exception) { return null }
        return try { encoded.remaining().takeIf { it <= limit } } finally {
            if (encoded.hasArray()) encoded.array().fill(0) else { encoded.clear(); while (encoded.hasRemaining()) encoded.put(0) }
        }
    }
    private fun sameOrigin(target: String): Boolean {
        if (utf8Size(target, 8192) == null || target.any { it.code <= 32 || it.code == 127 || it == '\\' }) return false
        val uri = try { URI(target) } catch (_: Exception) { return false }
        return uri.scheme == "http" && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.rawAuthority == origin && uri.host == "127.0.0.1" && uri.port in 1..65535
    }
    private fun command(job: Job): String {
        val headers = JSONArray(); job.headers.forEach { headers.put(JSONArray().put(it.first).put(it.second)) }
        val payload = JSONArray().put(job.method).put(job.target).put(headers).put(Base64.getEncoder().encodeToString(job.body))
        return "OPENFLUX_HTTP_REQUEST|$ticket|$permit|${job.id}|" +
            Base64.getEncoder().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    }
    /** A bounded queue serializes resource fanout: never send a second request
     * before END AND local response ownership transfer of the previous one. */
    @Synchronized fun enqueue(method: String, target: String, headers: List<Pair<String, String>>, body: ByteArray): String? {
        if (replyNonce != null) return null
        if (headers.size > 512) return null
        val headerSnapshot = try { headers.toList() } catch (_: Exception) { return null }
        if (!checkLive() || permit == null || !sameOrigin(target) || body.size > 65536 ||
            method !in METHODS || !OpenFluxHttpWire.headersAllowed(headerSnapshot) ||
            queue.size + (if (active == null) 0 else 1) >= 8) return null
        if (!initialTransferred && (active != null || queue.isNotEmpty() || method != "GET" || target != address || body.isNotEmpty())) return null
        val size = body.size + (utf8Size(target, 8192) ?: return null) +
            headerSnapshot.sumOf { (utf8Size(it.first, 8192) ?: return null) + (utf8Size(it.second, 8192) ?: return null) + 4 }
        if (reserved + size > 131072) return null
        val id = freshId() ?: return null
        val job = Job(id, method, target, headerSnapshot, body.copyOf(), size)
        if (command(job).length > 131071) { job.clear(); return null } // Writer budget includes the terminal newline.
        reserved += size; queue.add(job); return id
    }
    /** Returned private command is immutable and cannot be wiped after handoff.
     * No retry if the caller is uncertain whether this command reached native. */
    @Synchronized fun nextCommand(): String? {
        if (!checkLive() || active != null || replyNonce != null) return null
        val job = queue.poll() ?: return null; active = job
        return command(job)
    }
    private fun reject(): Received { close(); return Received.REJECTED }
    @Synchronized fun receive(expectedChild: String, frame: ByteArray): Received {
        if (!OpenFluxHttpWire.recognized(frame)) return Received.NOT_HTTP
        if (expectedChild != child) return Received.CONSUMED
        OpenFluxHttpWire.pending(frame)?.let { return Received.CONSUMED } // Never auto-arms a window.
        OpenFluxHttpWire.opened(frame)?.let { opened ->
            if (opened.id != ticket || opened.request != openNonce) return Received.CONSUMED
            if (!live()) { close(); cancel(opened.permit); return Received.CONSUMED }
            if (permit != null) return reject()
            permit = opened.permit; address = opened.address; origin = URI(opened.address).rawAuthority
            return Received.CONSUMED
        }
        if (closed || openNonce == null) return Received.CONSUMED
        if (!checkLive()) return Received.CONSUMED
        OpenFluxHttpWire.result(frame)?.let { result ->
            if (result.id != ticket) return Received.CONSUMED
            if (result.request == replyNonce) {
                if (result.code != "accepted") return reject()
                replyAccepted = true
                return Received.COMPLETE // Native apply ACK, not a connection/traffic receipt.
            }
            if (result.request == openNonce || result.request == active?.id) return reject()
            return Received.CONSUMED
        }
        val job = active
        OpenFluxHttpWire.metadata(frame)?.let { value ->
            if (value.id != ticket || value.request != job?.id) return Received.CONSUMED
            val owned = job ?: return Received.CONSUMED
            if (metadata != null || complete || owned.method == "HEAD" && value.bytes != 0) return reject()
            metadata = value; bytes = ByteArray(value.bytes); received = 0; return Received.CONSUMED
        }
        OpenFluxHttpWire.chunk(frame)?.let { chunk ->
            try {
                if (chunk.id != ticket || chunk.request != job?.id) return Received.CONSUMED
                val target = bytes ?: return reject()
                val expected = minOf(8192, target.size - received)
                if (complete || expected <= 0 || chunk.offset != received || chunk.bytes.size != expected) return reject()
                chunk.bytes.copyInto(target, received); received += chunk.bytes.size; return Received.CONSUMED
            } finally { chunk.close() }
        }
        OpenFluxHttpWire.end(frame)?.let { end ->
            if (end.id != ticket || end.request != job?.id) return Received.CONSUMED
            val target = bytes ?: return reject(); val meta = metadata ?: return reject()
            if (complete || received != target.size) return reject()
            val hash = MessageDigest.getInstance("SHA-256").digest(target).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (hash != meta.sha256) return reject()
            complete = true; return Received.COMPLETE
        }
        return reject()
    }
    /** Complete/hash-verified binary ownership transfer, NOT browser render,
     * navigation, HTTP success, human Send, or resource-consumption receipt. */
    @Synchronized fun takeResponse(): Response? {
        if (!checkLive() || !complete) return null
        val job = active ?: return null; val meta = metadata ?: return null; val body = bytes ?: return null
        val response = Response(job.id, meta.status, meta.headers, body)
        bytes = null; metadata = null; received = 0; complete = false
        initialTransferred = initialTransferred || job.method == "GET" && job.target == address && meta.status in 200..299
        job.clear(); reserved -= job.reserved; active = null
        return response
    }
    /** Explicit native-app Send only; a page JS message may stage but never call
     * this. Complete local response transfer is NOT a renderer receipt: the UI
     * must independently require a visible current page and manual gesture. */
    @Synchronized fun replyAfterUserAction(action: OpenFluxWindowAdmission.Permit?, payload: String): String? {
        if (!checkLive() || !window.current(action) || !initialTransferred || replyNonce != null ||
            active != null || queue.isNotEmpty() || !OpenFluxOwnPageSession.admissible(payload)) return null
        val nativePermit = permit ?: return null
        val id = freshId(forReply = true) ?: return null
        val raw = payload.toByteArray(Charsets.UTF_8)
        return try {
            replyNonce = id
            "OPENFLUX_HTTP_REPLY|$ticket|$nativePermit|$id|" + Base64.getEncoder().encodeToString(raw)
        } finally { raw.fill(0) }
    }
    /** 0 not attempted, 1 awaiting native apply result, 2 exact native accepted;
     * -1 terminal/stale. Writer admission alone can NEVER produce 2. */
    @Synchronized fun replyStatus(): Int = if (!checkLive()) -1 else if (replyAccepted) 2 else if (replyNonce != null) 1 else 0
    @Synchronized override fun close() {
        permit?.let(::cancel); permit = null; address = null; origin = null
        closed = true; bytes?.fill(0); bytes = null; metadata = null; received = 0; complete = false
        active?.clear(); active = null; queue.forEach { it.clear() }; queue.clear(); reserved = 0; foreground = null
    }
    companion object {
        private val ID = Regex("[0-9a-f]{32}")
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    }
}
