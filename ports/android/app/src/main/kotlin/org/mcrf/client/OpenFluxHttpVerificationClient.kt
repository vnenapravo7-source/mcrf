package org.mcrf.client

import android.os.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Caller supplies ONE off-main lifecycle worker; no implicit threads/retry.
 * Future Activity must gate foreground/Open and prevent JS from invoking them.
 * ownerCurrent must be a pure nonblocking atomic/clock check, never UI/IPC.
 * signalClose is nonblocking; actual cleanup waits for any admitted Binder call. */
internal class OpenFluxHttpVerificationClient(
    private val endpoint: IBinder, private val id: String, private val ownerCurrent: () -> Boolean
) : AutoCloseable {
    class Snapshot(val code: Int, val address: String?) { override fun toString() = "OpenFluxHttpClientSnapshot(redacted)" }
    class Pull(val waiting: Boolean, val progress: Boolean = false, val response: OpenFluxHttpIpcResponse.Response? = null) {
        override fun toString() = "OpenFluxHttpClientPull(redacted)"
    }
    private class Job(val method: String, val target: String)
    private class RemoteMetadata(val status: Int, val headers: List<Pair<String, String>>, val total: Int, val hash: String)
    private class RemoteSnapshot(val code: Int, val remaining: Long, val address: String)
    private val lock = Any()
    private val alive = AtomicBoolean(true)
    private val dead = AtomicBoolean(false)
    private val peer = Binder()
    private val death = IBinder.DeathRecipient { dead.set(true); alive.set(false) } // Never wait on client lock or transact here.
    @Volatile private var deadline: Long? = null
    @Volatile private var state = Snapshot(0, null)
    private var attachAttempted = false
    private var linked = false
    private var closed = false
    private var openAttempted = false
    private var uploadAttempts = 0
    private var initialTransferred = false
    private var replyAttempted = false
    private val jobs = linkedMapOf<String, Job>()
    private val seenIds = mutableSetOf<String>()
    private var receiver: OpenFluxHttpIpcResponse? = null
    init {
        workerOnly()
        require(id.matches(OpenFluxVerificationUiEvents.ID))
        try {
            val started = System.nanoTime()
            endpoint.linkToDeath(death, 0); linked = true
            synchronized(lock) {
                attachAttempted = true
                applySnapshot(exchange(OpenFluxHttpIpc.ATTACH, { it.writeStrongBinder(peer) }, ::readSnapshot, requireLive = false), started)
            }
        } catch (_: Exception) {
            synchronized(lock) { retire() }
            throw IOException("OpenFlux HTTP verification unavailable")
        }
    }
    override fun toString() = "OpenFluxHttpVerificationClient(redacted)"
    private fun workerOnly() { check(Looper.myLooper() != Looper.getMainLooper()) { "OpenFlux HTTP IPC requires a worker" } }
    fun current(): Boolean {
        val end = deadline ?: return false
        return alive.get() && !dead.get() && end > System.nanoTime() &&
            try { ownerCurrent() } catch (_: Exception) { false }
    }
    fun snapshot(): Snapshot? = state.takeIf { current() } // Pure/nonblocking, never IPC on UI.
    fun signalClose() { alive.set(false) }
    private fun <T> exchange(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T, requireLive: Boolean = true): T {
        if (requireLive) check(current())
        check(!dead.get())
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(OpenFluxHttpIpc.DESCRIPTOR); data.writeString(id)
            if (code != OpenFluxHttpIpc.ATTACH) data.writeStrongBinder(peer)
            write(data)
            check(data.dataSize() <= OpenFluxHttpIpc.MAX_PARCEL && !data.hasFileDescriptors())
            check(endpoint.transact(code, data, reply, 0))
            check(reply.dataSize() <= OpenFluxHttpIpc.MAX_PARCEL && !reply.hasFileDescriptors() && OpenFluxParcel.readInt(reply) == 0)
            val value = read(reply); check(reply.dataAvail() == 0)
            if (requireLive) check(current())
            return value
        } finally { data.recycle(); reply.recycle() }
    }
    private fun readSnapshot(reply: Parcel): RemoteSnapshot {
        val code = OpenFluxParcel.readInt(reply); val remaining = OpenFluxParcel.readLong(reply)
        val address = requireNotNull(reply.readString())
        check(code in 0..2 && remaining in 1..150000)
        check(if (code == 2) OpenFluxHttpIpc.address(address) != null else address.isEmpty())
        return RemoteSnapshot(code, remaining, address)
    }
    private fun applySnapshot(value: RemoteSnapshot, requestStarted: Long): Snapshot {
        val old = state
        if (old.address != null) check(value.code == 2 && old.address == value.address)
        deadline = OpenFluxHttpIpc.deadline(deadline, requestStarted, value.remaining) ?: error("invalid deadline")
        check(current())
        return Snapshot(value.code, value.address.takeIf { value.code == 2 }).also { state = it }
    }
    private fun retire() {
        if (closed) return
        closed = true; alive.set(false); receiver?.close(); receiver = null; jobs.clear()
        if (attachAttempted && !dead.get()) runCatching {
            exchange(OpenFluxHttpIpc.RELEASE, read = { Unit }, requireLive = false)
        } // One terminal cleanup attempt, never retry uncertain OPEN/upload/COMMIT.
        if (linked) runCatching { endpoint.unlinkToDeath(death, 0) }
        linked = false
    }
    fun poll(): Snapshot? {
        workerOnly()
        return synchronized(lock) {
            if (!current()) { retire(); return@synchronized null }
            try {
                val started = System.nanoTime()
                applySnapshot(exchange(OpenFluxHttpIpc.POLL, read = ::readSnapshot), started)
            }
            catch (_: Exception) { retire(); null }
        }
    }
    fun foreground(resumed: Boolean): Boolean {
        workerOnly()
        return synchronized(lock) {
            if (!current()) { retire(); return@synchronized false }
            try {
                exchange(OpenFluxHttpIpc.FOREGROUND, { it.writeInt(if (resumed) 1 else 0) }, { Unit })
                if (!resumed) { attachAttempted = false; retire() }
                true
            } catch (_: Exception) { retire(); false }
        }
    }
    fun openAfterUserAction(): Boolean {
        workerOnly()
        return synchronized(lock) {
            if (!current() || openAttempted || state.code != 0) return@synchronized false
            openAttempted = true
            try { exchange(OpenFluxHttpIpc.OPEN, read = { Unit }); true }
            catch (_: Exception) { retire(); false }
        }
    }
    fun enqueue(method: String, target: String, headers: List<Pair<String, String>>, body: ByteArray): String? {
        workerOnly()
        return synchronized(lock) {
            if (!current()) { retire(); return@synchronized null }
            val address = state.address ?: return@synchronized null
            val uri = OpenFluxHttpIpc.address(target) ?: return@synchronized null
            val base = OpenFluxHttpIpc.address(address) ?: return@synchronized null
            if (replyAttempted || !openAttempted || state.code != 2 || uri.rawAuthority != base.rawAuthority || method !in METHODS ||
                jobs.size >= 8 || uploadAttempts >= 60 || body.size > OpenFluxHttpIpc.MAX_UPLOAD) return@synchronized null
            if (!initialTransferred && (jobs.isNotEmpty() || method != "GET" || target != address || body.isNotEmpty())) return@synchronized null
            val encoded = OpenFluxHttpIpc.encodeHeaders(headers) ?: return@synchronized null
            val owned = body.copyOf(); uploadAttempts++
            try {
                val upload = exchange(OpenFluxHttpIpc.BEGIN, {
                    it.writeString(method); it.writeString(target); it.writeString(encoded); it.writeInt(owned.size)
                }, { requireNotNull(it.readString()).also { token -> check(token.matches(HEX_ID)) } })
                var offset = 0
                while (offset < owned.size) {
                    val chunk = owned.copyOfRange(offset, minOf(offset + OpenFluxHttpIpc.CHUNK, owned.size))
                    try {
                        exchange(OpenFluxHttpIpc.PUSH, { it.writeString(upload); it.writeInt(offset); it.writeByteArray(chunk) }, { Unit })
                    } finally { chunk.fill(0) }
                    offset += OpenFluxHttpIpc.CHUNK
                }
                val request = exchange(OpenFluxHttpIpc.COMMIT, { it.writeString(upload) }, {
                    val accepted = OpenFluxParcel.readInt(it); check(accepted in 0..1)
                    if (accepted == 0) null else requireNotNull(it.readString()).also { value ->
                        check(value.matches(HEX_ID) && value !in seenIds && seenIds.size < 60)
                    }
                })
                if (request != null) { seenIds.add(request); jobs[request] = Job(method, target) }
                request
            } catch (_: Exception) { retire(); null } finally { owned.fill(0) }
        }
    }
    fun pull(request: String): Pull {
        workerOnly()
        return synchronized(lock) {
            if (!current()) { retire(); return@synchronized Pull(false) }
            val job = jobs.entries.firstOrNull()?.takeIf { it.key == request }?.value ?: return@synchronized Pull(false)
            try {
                if (receiver == null) {
                    val metadata = exchange(OpenFluxHttpIpc.METADATA, { it.writeString(request) }, {
                        val present = OpenFluxParcel.readInt(it); check(present in 0..1)
                        if (present == 0) null else {
                            val actual = requireNotNull(it.readString()); val status = OpenFluxParcel.readInt(it)
                            val headers = OpenFluxHttpIpc.headers(requireNotNull(it.readString())) ?: error("invalid metadata")
                            val total = OpenFluxParcel.readInt(it); val hash = requireNotNull(it.readString())
                            check(actual == request && (job.method != "HEAD" || total == 0))
                            RemoteMetadata(status, headers, total, hash)
                        }
                    })
                    if (metadata == null) return@synchronized Pull(true)
                    // Do not allocate a body until the WHOLE Parcel and liveness
                    // were accepted; otherwise trailing/death failure loses cleanup ownership.
                    receiver = OpenFluxHttpIpcResponse(request, metadata.status, metadata.headers, metadata.total, metadata.hash, ::current)
                }
                val assembler = requireNotNull(receiver); val offset = assembler.nextOffset()
                exchange(OpenFluxHttpIpc.PULL, { it.writeString(request); it.writeInt(offset) }, {
                    val actual = OpenFluxParcel.readInt(it); val terminal = OpenFluxParcel.readInt(it); check(terminal in 0..1)
                    val bytes = OpenFluxParcel.readBytes(it, OpenFluxHttpIpc.CHUNK)
                    try { check(assembler.append(request, actual, bytes, terminal == 1)) } finally { bytes.fill(0) }
                })
                val response = assembler.take(request) ?: return@synchronized Pull(true, progress = true)
                receiver = null; jobs.remove(request)
                if (!current()) { response.close(); retire(); return@synchronized Pull(false) }
                initialTransferred = initialTransferred || job.method == "GET" && job.target == state.address && response.status in 200..299
                Pull(false, response = response)
            } catch (_: Exception) { retire(); Pull(false) }
        }
    }
    /** Only a visible current page's explicit app Send may call this. Transfer
     * receipt here alone is not proof of render/manual gesture; never expose to JS. */
    fun submitAfterUserAction(payload: String): Boolean {
        workerOnly()
        return synchronized(lock) {
            if (!current() || replyAttempted || !initialTransferred || jobs.isNotEmpty() || receiver != null ||
                !OpenFluxOwnPageSession.admissible(payload)) return@synchronized false
            replyAttempted = true
            try {
                val accepted = exchange(OpenFluxHttpIpc.REPLY, { it.writeString(payload) }, {
                    OpenFluxParcel.readInt(it).also { value -> check(value in 0..1) }
                })
                if (accepted == 0) retire()
                accepted == 1 // Command admission ONLY; use replyStatus for exact native ACK.
            } catch (_: Exception) { retire(); false }
        }
    }
    fun replyStatus(): Int {
        workerOnly()
        return synchronized(lock) {
            if (!current()) { retire(); return@synchronized -1 }
            if (!replyAttempted) return@synchronized 0
            try { exchange(OpenFluxHttpIpc.REPLY_STATUS, read = { OpenFluxParcel.readInt(it).also { value -> check(value in 1..2) } }) }
            catch (_: Exception) { retire(); -1 }
        }
    }
    override fun close() { workerOnly(); synchronized(lock) { retire() } }
    companion object {
        private val HEX_ID = Regex("[0-9a-f]{32}")
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    }
}
