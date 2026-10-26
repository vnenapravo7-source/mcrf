package org.mcrf.client

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Ephemeral exact-child requests; no Android, URL persistence or provider access. */
internal class OpenFluxVerificationRegistry(private val now: () -> Long = System::nanoTime) {
    enum class Kind { EXTERNAL, INLINE, HTTP }
    class Request internal constructor(
        val child: String, val nativeId: String, val externalPage: OpenFluxVerificationPolicy.Page?,
        private val deadline: Long, private val now: () -> Long, private val childAlive: () -> Boolean,
        private val offer: (ByteArray) -> Boolean,
        val kind: Kind = if (externalPage == null) Kind.INLINE else Kind.EXTERNAL,
    ) {
        val id = UUID.randomUUID().toString()
        val opened = AtomicBoolean(false)
        val submitted = AtomicBoolean(false)
        internal val retired = AtomicBoolean(false)
        val inline: Boolean get() = kind == Kind.INLINE
        val http: Boolean get() = kind == Kind.HTTP
        init { require((kind == Kind.EXTERNAL) == (externalPage != null)) }
        // Existing v1 cookie/UI consumers must never reinterpret an inline page as a URL.
        val page: OpenFluxVerificationPolicy.Page get() { check(kind == Kind.EXTERNAL); return requireNotNull(externalPage) }
        private val browserActive = AtomicBoolean(false)
        private val browserEpoch = AtomicLong()
        private val document = if (inline) OpenFluxDocumentReceiver(child, ::alive) else null
        private val documentEpoch = AtomicLong()
        private val documentReadAccepted = AtomicBoolean(false)
        private val deliveryLock = Any()
        private var delivery: OpenFluxDocumentDelivery? = null
        private var deliveryStarted = false
        private val deliveryComplete = AtomicBoolean(false)
        private val httpLock = Any()
        private var httpSession: OpenFluxHttpSession? = null
        class Pull(val waiting: Boolean, val chunk: OpenFluxDocumentDelivery.Chunk? = null) {
            override fun toString() = "OpenFluxDocumentPull(redacted)"
        }
        private val reply = OpenFluxVerificationReply(child, nativeId, ::alive, offer)
        fun alive(): Boolean = !retired.get() && now() < deadline && try { childAlive() } catch (_: Exception) { false }
        fun remainingMillis(): Long = ((deadline - now()) / 1_000_000).coerceAtLeast(0)
        @Synchronized fun submit(cookies: String): Boolean {
            if (kind != Kind.EXTERNAL || !alive() || submitted.get()) return false
            val accepted = reply.submit(child, nativeId, cookies)
            if (accepted) submitted.set(true)
            return accepted
        }
        internal fun claimDocumentBrowser() { browserEpoch.incrementAndGet(); browserActive.set(true) }
        internal fun releaseDocumentBrowser() {
            browserActive.set(false); browserEpoch.incrementAndGet()
            // Receiver callbacks below read only atomics/liveness, never the registry lock.
            document?.close()
            synchronized(deliveryLock) { delivery?.close(); delivery = null }
            synchronized(httpLock) { httpSession?.close() }
        }
        /** Disabled production foundation. Future exact HTTP endpoint owns this
         * foreground gate; remote flags alone are not proof of a manual gesture.
         * Returned bytes must be offered once to this child outside registry locks
         * and wiped by their caller; no implicit write or retry occurs here. */
        fun openHttpAfterUserAction(window: OpenFluxWindowAdmission, action: OpenFluxWindowAdmission.Permit?): ByteArray? =
            synchronized(httpLock) {
                if (!http || !alive() || !browserActive.get() || submitted.get() || !window.current(action) || httpSession != null)
                    return@synchronized null
                val epoch = browserEpoch.get()
                val session = try { OpenFluxHttpSession(child, nativeId, deadline, {
                    browserActive.get() && browserEpoch.get() == epoch && alive()
                }, window, now) } catch (_: Exception) { return@synchronized null }
                httpSession = session
                session.openAfterUserAction(action)?.let { (it + "\n").toByteArray(Charsets.US_ASCII) }
            }
        fun enqueueHttp(method: String, target: String, headers: List<Pair<String, String>>, body: ByteArray): String? =
            synchronized(httpLock) { httpSession?.enqueue(method, target, headers, body) }
        fun nextHttpCommand(): ByteArray? = synchronized(httpLock) {
            httpSession?.nextCommand()?.let { (it + "\n").toByteArray(Charsets.US_ASCII) }
        }
        fun receiveHttp(expectedChild: String, bytes: ByteArray): OpenFluxHttpSession.Received = synchronized(httpLock) {
            httpSession?.receive(expectedChild, bytes) ?: if (OpenFluxHttpWire.recognized(bytes))
                OpenFluxHttpSession.Received.CONSUMED else OpenFluxHttpSession.Received.NOT_HTTP
        }
        fun takeHttpResponse(): OpenFluxHttpSession.Response? = synchronized(httpLock) { httpSession?.takeResponse() }
        fun replyHttpAfterUserAction(window: OpenFluxWindowAdmission, action: OpenFluxWindowAdmission.Permit?, payload: String): ByteArray? =
            synchronized(httpLock) {
                if (!http || !alive() || !browserActive.get() || submitted.get() || !window.current(action)) return@synchronized null
                httpSession?.replyAfterUserAction(action, payload)?.let { (it + "\n").toByteArray(Charsets.US_ASCII) }
            }
        fun httpReplyStatus(): Int = synchronized(httpLock) {
            if (!http || !alive() || !browserActive.get()) -1 else (httpSession?.replyStatus() ?: 0).also {
                if (it == 2) submitted.set(true)
            }
        }
        fun httpStatus(): OpenFluxHttpSession.Status = synchronized(httpLock) {
            if (!http || !alive() || !browserActive.get()) OpenFluxHttpSession.Status(-1)
            else httpSession?.status() ?: OpenFluxHttpSession.Status(0)
        }
        /** Only internal protocol-generated bytes reach the captured exact-child
         * bounded writer callback. No registry/http/session lock held over offer.
         * Writer must defensively copy; caller always wipes its admitted original. */
        fun offerHttpCommand(command: ByteArray): Boolean {
            if (!http || !alive() || !browserActive.get() || command.size !in 1..131072 ||
                command.last() != 10.toByte() || httpStatus().code < 1) return false
            for (index in 0 until command.lastIndex) if (command[index].toInt() !in 32..126) return false
            val parts = String(command, 0, minOf(128, command.size), Charsets.US_ASCII).split('|', limit = 3)
            if (parts.size != 3 || parts[1] != nativeId ||
                parts[0] !in setOf("OPENFLUX_HTTP_OPEN", "OPENFLUX_HTTP_REQUEST", "OPENFLUX_HTTP_REPLY", "OPENFLUX_HTTP_CLOSE")) return false
            return try { offer(command) } catch (_: Exception) { false }
        }
        fun takeHttpCancelCommand(): ByteArray? = synchronized(httpLock) {
            httpSession?.takeCancelCommand()?.let { (it + "\n").toByteArray(Charsets.US_ASCII) }
        }
        @Synchronized fun requestDocumentAfterUserOpen(): Boolean {
            if (!inline || !alive() || !browserActive.get()) return false
            val epoch = browserEpoch.get()
            // Wire v1 has no read-attempt nonce. After release, delayed frames of
            // this ticket cannot be distinguished from a second read. Fail closed:
            // one document admission per native ticket, even after UI recreation.
            if (documentEpoch.get() != 0L) return false
            val pending = OpenFluxDocumentWire.Pending(nativeId)
            val armed = document!!.requestAfterUserOpen(pending) {
                browserActive.get() && browserEpoch.get() == epoch && alive()
            }
            if (!armed) return false
            documentEpoch.set(epoch)
            val command = OpenFluxOwnCommand.read(nativeId) ?: return false
            val accepted = try { try { offer(command) } catch (_: Exception) { false } } finally { command.fill(0) }
            if (accepted) documentReadAccepted.set(true) else document?.close() // No automatic retry after an uncertain queue outcome.
            return accepted
        }
        fun receiveDocument(expectedChild: String, bytes: ByteArray): OpenFluxDocumentReceiver.Result =
            document?.receive(expectedChild, bytes) ?: if (OpenFluxDocumentWire.recognized(bytes))
                OpenFluxDocumentReceiver.Result.REJECTED else OpenFluxDocumentReceiver.Result.NOT_DOCUMENT
        fun takeDocument(expectedChild: String, expectedTicket: String): String? = document?.take(expectedChild, expectedTicket)
        private fun documentOwned() = inline && alive() && browserActive.get() && documentReadAccepted.get() &&
            documentEpoch.get() != 0L && documentEpoch.get() == browserEpoch.get()
        fun pullDocument(offset: Int): Pull = synchronized(deliveryLock) {
            if (!documentOwned() || submitted.get() || offset !in 0 until OpenFluxOwnDocument.MAX_BYTES || offset % 8192 != 0)
                return@synchronized Pull(false)
            if (delivery == null) {
                if (deliveryStarted || offset != 0) return@synchronized Pull(false)
                val html = document?.take(child, nativeId) ?: return@synchronized Pull(true)
                deliveryStarted = true
                delivery = try { OpenFluxDocumentDelivery(html) } catch (_: Exception) { null }
            }
            val chunk = delivery?.read(offset) ?: return@synchronized Pull(false)
            if (chunk.offset + chunk.bytes.size == chunk.total) {
                deliveryComplete.set(true); delivery?.close(); delivery = null
            }
            Pull(false, chunk)
        }
        @Synchronized fun submitOwn(payload: String): Boolean {
            if (!documentOwned() || !deliveryComplete.get() || submitted.get()) return false
            val bytes = OpenFluxOwnCommand.reply(nativeId, payload) ?: return false
            val accepted = try { try { offer(bytes) } catch (_: Exception) { false } } finally { bytes.fill(0) }
            if (accepted) submitted.set(true)
            return accepted
        }
        internal fun cancelNative(): Boolean {
            val bytes = "OPENFLUX_CAPTCHA_CANCEL|$nativeId\n".toByteArray(Charsets.US_ASCII)
            return try { try { offer(bytes) } catch (_: Exception) { false } } finally { bytes.fill(0) }
        }
        override fun toString() = "OpenFluxVerificationRequest(redacted)"
    }
    private val lock = Any()
    private val requests = linkedMapOf<String, Request>()
    private var browserOwner: String? = null

    fun register(child: String, nativeId: String, page: OpenFluxVerificationPolicy.Page,
                 childAlive: () -> Boolean, offer: (ByteArray) -> Boolean): Request? = registerRequest(child, nativeId, page, childAlive, offer)
    /** Not a UI capability: the production document dispatcher/renderer remains disabled. */
    fun registerInline(child: String, pending: OpenFluxDocumentWire.Pending,
                       childAlive: () -> Boolean, offer: (ByteArray) -> Boolean): Request? =
        registerRequest(child, pending.id, null, childAlive, offer)
    /** Same shared32/onechild/onebrowser pool, NOT a production capability. */
    fun registerHttp(child: String, pending: OpenFluxHttpWire.Pending,
                     childAlive: () -> Boolean, offer: (ByteArray) -> Boolean): Request? =
        registerRequest(child, pending.id, null, childAlive, offer, Kind.HTTP)
    private fun registerRequest(child: String, nativeId: String, page: OpenFluxVerificationPolicy.Page?,
                                childAlive: () -> Boolean, offer: (ByteArray) -> Boolean,
                                kind: Kind = if (page == null) Kind.INLINE else Kind.EXTERNAL): Request? = synchronized(lock) {
        require(child.matches(Regex("[0-9a-f]{32}")) && nativeId.matches(Regex("[0-9a-f]{32}")))
        requests.values.filter { !it.alive() }.toList().forEach { removeLocked(it) }
        requests.values.firstOrNull { it.child == child }?.let { old ->
            if (old.nativeId == nativeId) {
                // A ticket cannot change protocol/URL under an existing opaque UI ID.
                return@synchronized old.takeIf { it.kind == kind && it.externalPage?.url == page?.url && it.externalPage?.origin == page?.origin }
            } // No duplicate deadline renewal.
            removeLocked(old)
        }
        if (requests.size >= 32) return@synchronized null
        Request(child, nativeId, page, now() + 150_000_000_000L, now, childAlive, offer, kind).also { requests[it.id] = it }
    }
    private fun removeLocked(request: Request) {
        requests.remove(request.id); request.retired.set(true)
        request.releaseDocumentBrowser()
        if (browserOwner == request.id) browserOwner = null
    }
    fun get(id: String): Request? = synchronized(lock) {
        requests[id]?.let { if (it.alive()) it else { removeLocked(it); null } }
    }
    // The current cookie-only Activity must not be offered an inline request.
    fun unopened(includeInline: Boolean = false, includeHttp: Boolean = false): Request? = synchronized(lock) { requests.values.firstOrNull {
        (includeHttp || !it.http) && (includeInline || !it.inline) && it.alive() && !it.opened.get() &&
            (!it.http || !it.submitted.get())
    } }
    fun browserOwnedBy(id: String): Boolean = synchronized(lock) { browserOwner == id && requests[id]?.alive() == true }
    fun claimBrowser(id: String): Boolean = synchronized(lock) {
        // A paused Activity does not poll. Its expired/dead request cannot keep
        // the single browser slot forever while an already-queued request waits.
        browserOwner?.let { owner ->
            val retained = requests[owner]
            if (retained == null) browserOwner = null else if (!retained.alive()) removeLocked(retained)
        }
        val request = requests[id] ?: return@synchronized false
        if (!request.alive() || browserOwner != null) return@synchronized false
        browserOwner = id; request.opened.set(true); request.claimDocumentBrowser(); true
    }
    fun releaseBrowser(id: String) {
        val terminalHttp = synchronized(lock) {
            if (browserOwner != id) return@synchronized null
            browserOwner = null
            val request = requests[id]
            if (request?.http == true) { removeLocked(request); request }
            else { request?.releaseDocumentBrowser(); null }
        }
        terminalHttp?.cancelNative() // Retires exact native ticket even before OPENED/permit arrived.
    }
    fun reopenAfterRecreate(id: String): Boolean = synchronized(lock) {
        val request = requests[id] ?: return@synchronized false
        if (request.http || !request.alive() || request.submitted.get() || browserOwner == id) return@synchronized false
        request.opened.set(false); true // Same request/deadline, not a new native attempt.
    }
    fun cancel(id: String): Boolean {
        val request = synchronized(lock) { requests[id]?.also { removeLocked(it) } } ?: return true
        return request.cancelNative() // Never call an IPC writer under the registry lock.
    }
    fun complete(child: String, nativeId: String): String? = synchronized(lock) {
        requests.values.firstOrNull { it.child == child && it.nativeId == nativeId }?.let { removeLocked(it); it.id }
    }
    fun closeChild(child: String): List<String> = synchronized(lock) {
        requests.values.filter { it.child == child }.toList().map { removeLocked(it); it.id }
    }
}

internal object OpenFluxVerificationRequests { val registry = OpenFluxVerificationRegistry() }
