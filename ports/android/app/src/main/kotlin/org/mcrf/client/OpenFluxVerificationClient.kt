package org.mcrf.client

import android.os.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded private Binder client; URL/cookies stay in memory and are never Intent extras. */
internal class OpenFluxVerificationClient(private val endpoint: IBinder, private val id: String) {
    class Request internal constructor(val id: String, val page: OpenFluxVerificationPolicy.Page,
                                       private val deadline: Long, private val submitter: (String) -> Boolean) {
        val submitted = AtomicBoolean(false)
        fun remainingMillis() = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        fun submit(cookies: String) = submitter(cookies)
        override fun toString() = "OpenFluxVerificationRemoteRequest(redacted)"
    }
    private val alive = AtomicBoolean(true)
    private val peer = Binder()
    private val death = IBinder.DeathRecipient { alive.set(false) }
    @Volatile private var request: Request? = null
    init {
        require(id.matches(ID))
        endpoint.linkToDeath(death, 0)
        try { exchange(1, { it.writeStrongBinder(peer) }, ::readSnapshot); check(request != null) }
        catch (failure: Exception) { alive.set(false); runCatching { endpoint.unlinkToDeath(death, 0) }; throw IOException("OpenFlux verification unavailable") }
    }
    private fun <T> exchange(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(id); write(data)
            check(data.dataSize() <= 32768 && endpoint.transact(code, data, reply, 0))
            check(reply.dataSize() <= 32768 && !reply.hasFileDescriptors() && OpenFluxParcel.readInt(reply) == 0)
            val result = read(reply); check(reply.dataAvail() == 0); return result
        } finally { data.recycle(); reply.recycle() }
    }
    private fun readSnapshot(reply: Parcel) {
        val present = OpenFluxParcel.readInt(reply); check(present in 0..1)
        if (present == 0) { alive.set(false); return }
        val url = requireNotNull(reply.readString()); val origin = requireNotNull(reply.readString())
        val remaining = OpenFluxParcel.readLong(reply); val submitted = OpenFluxParcel.readInt(reply)
        check(url.length <= 8192 && origin.length <= 261 && remaining in 1..150000 && submitted in 0..1)
        val page = requireNotNull(OpenFluxVerificationPolicy.externalPage(url, "", false, "")); check(page.origin == origin)
        val current = request
        if (current == null) request = Request(id, page, System.nanoTime() + remaining * 1_000_000, ::submit)
        else check(current.page.url == page.url && current.page.origin == page.origin) // No origin/URL replacement behind this UI ID.
        request?.submitted?.set(submitted == 1)
    }
    fun get(expected: String): Request? = request?.takeIf { expected == id && alive.get() && it.remainingMillis() > 0 }
    fun poll() { if (alive.get()) try { exchange(2, {}, ::readSnapshot) } catch (_: Exception) { alive.set(false) } }
    private fun submit(cookies: String): Boolean {
        val current = get(id) ?: return false
        if (current.submitted.get() || cookies.length !in 1..8192 || cookies.any { it.code !in 0x20..0x7e }) return false
        return try {
            exchange(3, { it.writeString(cookies) }, { val accepted = OpenFluxParcel.readInt(it); check(accepted in 0..1); accepted == 1 })
                .also { if (it) current.submitted.set(true) }
        } catch (_: Exception) { alive.set(false); false }
    }
    fun close(keepForRecreate: Boolean) {
        if (alive.get()) runCatching { exchange(4, { it.writeInt(if (keepForRecreate) 1 else 0) }, {}) }
        alive.set(false); runCatching { endpoint.unlinkToDeath(death, 0) }
    }
    companion object {
        const val DESCRIPTOR = "org.mcrf.client.OpenFluxVerification.v1"
        val ID = OpenFluxVerificationUiEvents.ID
    }
}
