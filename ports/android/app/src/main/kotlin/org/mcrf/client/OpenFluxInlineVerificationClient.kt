package org.mcrf.client

import android.os.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Separate bounded Binder contract for own-inline pages, not a cookie/URL API.
 * Source-wired to the private own Activity behind the disabled production gate.
 * Caller must poll/close on its lifecycle
 * and perform chunk reads off the UI thread; no implicit load or browser open. */
internal class OpenFluxInlineVerificationClient(private val endpoint: IBinder, private val id: String) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val submitted = AtomicBoolean(false)
    private val peer = Binder()
    private val death = IBinder.DeathRecipient { alive.set(false); invalidate() }
    @Volatile private var deadline = 0L
    private var total: Int? = null
    private var digest: String? = null
    private var expectedOffset = 0
    private var readStarted = false
    private var readAccepted = false
    private var document: OpenFluxOwnDocument? = null
    private var documentVerified = false
    private val nonce = id.replace("-", "")
    init {
        require(id.matches(OpenFluxVerificationClient.ID))
        endpoint.linkToDeath(death, 0)
        try { exchange(1, { it.writeStrongBinder(peer) }, ::snapshot); check(current()) }
        catch (_: Exception) {
            alive.set(false); runCatching { endpoint.unlinkToDeath(death, 0) }
            throw IOException("OpenFlux inline verification unavailable")
        }
    }
    override fun toString() = "OpenFluxInlineVerificationClient(redacted)"
    @Synchronized private fun invalidate() { alive.set(false); document?.close(); document = null }
    fun current() = alive.get() && deadline > System.nanoTime() // Pure nonblocking UI/assembler predicate.
    @Synchronized private fun <T> exchange(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(id); write(data)
            check(data.dataSize() <= 32768 && endpoint.transact(code, data, reply, 0))
            check(reply.dataSize() <= 32768 && !reply.hasFileDescriptors() && OpenFluxParcel.readInt(reply) == 0)
            val result = read(reply); check(reply.dataAvail() == 0); return result
        } finally { data.recycle(); reply.recycle() }
    }
    private fun snapshot(reply: Parcel) {
        val present = OpenFluxParcel.readInt(reply); check(present in 0..1)
        if (present == 0) { invalidate(); return }
        val remaining = OpenFluxParcel.readLong(reply); val sent = OpenFluxParcel.readInt(reply)
        check(remaining in 1..150000 && sent in 0..1 && (!submitted.get() || sent == 1))
        if (deadline == 0L) deadline = System.nanoTime() + remaining * 1_000_000
        submitted.set(sent == 1) // Poll never renews the original deadline.
    }
    @Synchronized fun poll() {
        if (!current()) { invalidate(); return }
        try { exchange(2, {}, ::snapshot) } catch (_: Exception) { invalidate() }
    }
    @Synchronized fun beginAfterUserOpen(): Boolean {
        if (!current() || readStarted || submitted.get()) return false
        readStarted = true // No automatic repeat after an uncertain transaction outcome.
        return try { exchange(5, {}, { val accepted = OpenFluxParcel.readInt(it); check(accepted in 0..1); accepted == 1 }).also { readAccepted = it } }
        catch (_: Exception) { invalidate(); false }
    }
    class Pull(val waiting: Boolean, val html: String? = null, val progress: Boolean = false) {
        override fun toString() = "OpenFluxInlineRemotePull(redacted)"
    }
    @Synchronized fun pull(): Pull {
        if (!current()) { invalidate(); return Pull(false) }
        if (!readAccepted || submitted.get() || total?.let { expectedOffset >= it } == true) return Pull(false)
        return try { exchange(6, { it.writeInt(expectedOffset) }) { reply ->
            when (OpenFluxParcel.readInt(reply)) {
                0 -> Pull(true)
                -1 -> { invalidate(); Pull(false) }
                1 -> {
                    val size = OpenFluxParcel.readInt(reply); val hash = requireNotNull(reply.readString()); val offset = OpenFluxParcel.readInt(reply)
                    check(size in 1..OpenFluxOwnDocument.MAX_BYTES && hash.matches(Regex("[0-9a-f]{64}")) && offset == expectedOffset)
                    check(total == null || total == size && digest == hash)
                    val position = reply.dataPosition(); val length = OpenFluxParcel.readInt(reply)
                    check(length == minOf(8192, size - offset) && length in 1..8192)
                    check((length + 3) / 4 * 4 <= reply.dataAvail())
                    val bytes = ByteArray(length)
                    try {
                        reply.setDataPosition(position); reply.readByteArray(bytes)
                        check(reply.dataAvail() == 0)
                        total = size; digest = hash
                        val receiver = document ?: OpenFluxOwnDocument(nonce, nonce, size, hash, ::current).also { document = it }
                        check(receiver.append(nonce, nonce, offset, bytes)); expectedOffset += length
                        if (expectedOffset == size) {
                            val html = requireNotNull(receiver.take(nonce, nonce)); document = null
                            check(current()); documentVerified = true
                            Pull(false, html)
                        } else Pull(true, progress = true)
                    } finally { bytes.fill(0) }
                }
                else -> error("OpenFlux inline response rejected")
            }
        } } catch (_: Exception) { invalidate(); Pull(false) }
    }
    @Synchronized fun submitAfterUserAction(payload: String): Boolean {
        if (!current() || submitted.get() || !documentVerified || !OpenFluxOwnPageSession.admissible(payload)) return false
        return try { exchange(3, { it.writeString(payload) }, { val accepted = OpenFluxParcel.readInt(it); check(accepted in 0..1); accepted == 1 })
            .also { if (it) submitted.set(true) }
        } catch (_: Exception) { invalidate(); false }
    }
    @Synchronized override fun close() {
        if (alive.get()) runCatching { exchange(4, { it.writeInt(0) }, {}) }
        invalidate(); runCatching { endpoint.unlinkToDeath(death, 0) }
    }
    companion object { const val DESCRIPTOR = "org.mcrf.client.OpenFluxInlineVerification.v1" }
}
