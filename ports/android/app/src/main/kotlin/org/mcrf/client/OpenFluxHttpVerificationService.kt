package org.mcrf.client

import android.app.Service
import android.content.Intent
import android.os.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Private main-process owner; HTTP Activity/client source exists, production
 * capability remains off. A Binder OPEN/FOREGROUND is not gesture evidence. */
class OpenFluxHttpVerificationService : Service() {
    private val endpoints = mutableMapOf<String, Endpoint>()
    private val registry get() = OpenFluxVerificationRequests.registry
    private val expiry = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mcrf-openflux-http-expiry").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    override fun onBind(intent: Intent): IBinder? {
        val id = intent.getStringExtra("request") ?: return null
        val key = intent.dataString ?: return null
        if (!id.matches(OpenFluxVerificationUiEvents.ID) || key.length > 160) return null
        val request = registry.get(id)?.takeIf { it.http } ?: return null
        return synchronized(endpoints) {
            if (endpoints.size >= 32 || endpoints.containsKey(key)) null
            else Endpoint(request).also { endpoints[key] = it }
        }
    }
    override fun onUnbind(intent: Intent): Boolean {
        synchronized(endpoints) { endpoints.remove(intent.dataString) }?.release()
        return false
    }
    override fun onDestroy() {
        val retained = synchronized(endpoints) { endpoints.values.toList().also { endpoints.clear() } }
        try { retained.forEach { runCatching { it.release() } } }
        finally { expiry.shutdown(); super.onDestroy() }
    }
    private class Upload(val body: OpenFluxHttpUpload, val method: String, val target: String,
                         val headers: List<Pair<String, String>>)
    private inner class Endpoint(private val request: OpenFluxVerificationRegistry.Request) : Binder() {
        private val id = request.id
        private var closed = false
        private var owns = false
        private var pid = 0
        private var peer: IBinder? = null
        private var timer: ScheduledFuture<*>? = null
        private val window = OpenFluxWindowAdmission()
        private var upload: Upload? = null
        private var delivery: OpenFluxHttpResponseDelivery? = null
        private var inflight: String? = null
        private val death = IBinder.DeathRecipient { release() }
        private fun current() = !closed && owns && request.alive() && registry.browserOwnedBy(id) &&
            registry.get(id) === request && request.httpStatus().code >= 0
        @Synchronized fun release() {
            if (closed) return
            closed = true; window.close(); timer?.cancel(false); timer = null
            upload?.body?.close(); upload = null; delivery?.close(); delivery = null; inflight = null
            runCatching { peer?.unlinkToDeath(death, 0) }; peer = null
            val retained = owns; owns = false
            if (retained) registry.cancel(id) // Exact native ticket, including a not-yet-received OPENED.
        }
        private fun offer(bytes: ByteArray): Boolean = try { request.offerHttpCommand(bytes) } finally { bytes.fill(0) }
        private fun pump() {
            check(current())
            if (delivery != null || inflight != null || request.httpStatus().code != 2) return
            val bytes = request.nextHttpCommand() ?: return
            val prefix = String(bytes, 0, minOf(160, bytes.size), Charsets.US_ASCII).split('|', limit = 5)
            if (prefix.size != 5 || !prefix[3].matches(HEX_ID)) { bytes.fill(0); error("invalid scoped command") }
            inflight = prefix[3]
            check(offer(bytes)) // Uncertain admission is terminal, never retried.
        }
        private fun snapshot(reply: Parcel) {
            check(current())
            val state = request.httpStatus(); check(state.code >= 0)
            reply.writeInt(state.code); reply.writeLong(request.remainingMillis()); reply.writeString(state.address.orEmpty())
        }
        private fun validTarget(target: String): Boolean {
            if (OpenFluxHttpIpc.utf8Size(target, 8192) == null ||
                target.any { it.code <= 32 || it.code == 127 || it == '\\' }) return false
            val base = request.httpStatus().address ?: return false
            return try {
                val uri = URI(target)
                uri.scheme == "http" && uri.host == "127.0.0.1" && uri.rawAuthority == URI(base).rawAuthority &&
                    uri.rawUserInfo == null && uri.rawFragment == null
            } catch (_: Exception) { false }
        }
        @Synchronized override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (Binder.getCallingUid() != android.os.Process.myUid() ||
                Binder.getCallingPid() == android.os.Process.myPid() ||
                data.dataSize() > OpenFluxHttpIpc.MAX_PARCEL || data.hasFileDescriptors()) return false
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply == null || data.dataAvail() != 0 || flags and IBinder.FLAG_ONEWAY != 0) return false
                reply.setDataPosition(0); reply.setDataSize(0); reply.writeString(OpenFluxHttpIpc.DESCRIPTOR); return true
            }
            if (code !in OpenFluxHttpIpc.ATTACH..OpenFluxHttpIpc.REPLY_STATUS || reply == null || flags and IBinder.FLAG_ONEWAY != 0) return false
            val callingOwner = owns && pid == Binder.getCallingPid()
            var acquired = false
            var tokenOwned = false
            try {
                data.enforceInterface(OpenFluxHttpIpc.DESCRIPTOR)
                check(data.readString() == id && !closed)
                if (code != OpenFluxHttpIpc.ATTACH) {
                    check(callingOwner && data.readStrongBinder() == peer)
                    tokenOwned = true
                    check(current())
                }
                reply.setDataPosition(0); reply.setDataSize(0)
                when (code) {
                    OpenFluxHttpIpc.ATTACH -> {
                        check(!owns); val token = requireNotNull(data.readStrongBinder()); check(data.dataAvail() == 0)
                        check(registry.get(id) === request && request.http && registry.claimBrowser(id))
                        owns = true; acquired = true; pid = Binder.getCallingPid(); peer = token
                        token.linkToDeath(death, 0)
                        timer = expiry.schedule({ release() }, request.remainingMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
                        reply.writeInt(0); snapshot(reply)
                    }
                    OpenFluxHttpIpc.POLL -> {
                        check(data.dataAvail() == 0); pump(); reply.writeInt(0); snapshot(reply)
                    }
                    OpenFluxHttpIpc.FOREGROUND -> {
                        val resumed = OpenFluxParcel.readInt(data); check(resumed in 0..1 && data.dataAvail() == 0)
                        if (resumed == 0) release() else window.resume()
                        reply.writeInt(0)
                    }
                    OpenFluxHttpIpc.OPEN -> {
                        check(data.dataAvail() == 0)
                        val bytes = request.openHttpAfterUserAction(window, window.capture()) ?: error("open unavailable")
                        check(offer(bytes)); reply.writeInt(0)
                    }
                    OpenFluxHttpIpc.BEGIN -> {
                        val method = requireNotNull(data.readString()); val target = requireNotNull(data.readString())
                        val text = requireNotNull(data.readString()); val total = OpenFluxParcel.readInt(data)
                        check(data.dataAvail() == 0 && upload == null && method in METHODS &&
                            total in 0..OpenFluxHttpIpc.MAX_UPLOAD && validTarget(target))
                        val headers = OpenFluxHttpIpc.headers(text) ?: error("invalid headers")
                        val uploadId = UUID.randomUUID().toString().replace("-", "")
                        upload = Upload(OpenFluxHttpUpload(uploadId, total, ::current), method, target, headers)
                        reply.writeInt(0); reply.writeString(uploadId)
                    }
                    OpenFluxHttpIpc.PUSH -> {
                        val uploadId = requireNotNull(data.readString()); val offset = OpenFluxParcel.readInt(data)
                        val bytes = OpenFluxParcel.readBytes(data, OpenFluxHttpIpc.CHUNK)
                        try {
                            check(data.dataAvail() == 0 && upload?.body?.append(uploadId, offset, bytes) == true)
                        } finally { bytes.fill(0) }
                        reply.writeInt(0)
                    }
                    OpenFluxHttpIpc.COMMIT -> {
                        val uploadId = requireNotNull(data.readString()); check(data.dataAvail() == 0)
                        val prepared = upload ?: error("upload unavailable")
                        val bytes = prepared.body.take(uploadId) ?: error("upload incomplete")
                        upload = null
                        val accepted = try { request.enqueueHttp(prepared.method, prepared.target, prepared.headers, bytes) }
                            finally { bytes.fill(0); prepared.body.close() }
                        reply.writeInt(0); reply.writeInt(if (accepted == null) 0 else 1)
                        if (accepted != null) reply.writeString(accepted)
                        pump()
                    }
                    OpenFluxHttpIpc.METADATA -> {
                        val requestId = requireNotNull(data.readString()); check(requestId.matches(HEX_ID) && data.dataAvail() == 0)
                        check(requestId == inflight)
                        if (delivery == null) request.takeHttpResponse()?.let { response ->
                            if (response.request != requestId) { response.close(); error("wrong response") }
                            delivery = OpenFluxHttpResponseDelivery.adopt(response, ::current) ?: error("response unavailable")
                        }
                        val meta = delivery?.metadata(requestId)
                        reply.writeInt(0); reply.writeInt(if (meta == null) 0 else 1)
                        if (meta != null) {
                            reply.writeString(meta.request); reply.writeInt(meta.status); reply.writeString(meta.headersJson)
                            reply.writeInt(meta.total); reply.writeString(meta.sha256)
                        }
                    }
                    OpenFluxHttpIpc.PULL -> {
                        val requestId = requireNotNull(data.readString()); val offset = OpenFluxParcel.readInt(data)
                        check(data.dataAvail() == 0 && requestId == inflight)
                        val chunk = delivery?.read(requestId, offset) ?: error("response unavailable")
                        try {
                            reply.writeInt(0); reply.writeInt(chunk.offset); reply.writeInt(if (chunk.last) 1 else 0)
                            reply.writeByteArray(chunk.bytes)
                            if (chunk.last) { delivery?.close(); delivery = null; inflight = null }
                        } finally { chunk.close() }
                    }
                    OpenFluxHttpIpc.RELEASE -> { check(data.dataAvail() == 0); release(); reply.writeInt(0) }
                    OpenFluxHttpIpc.REPLY -> {
                        val payload = requireNotNull(data.readString())
                        check(data.dataAvail() == 0 && upload == null && delivery == null && inflight == null &&
                            OpenFluxOwnPageSession.admissible(payload))
                        val bytes = request.replyHttpAfterUserAction(window, window.capture(), payload)
                        if (bytes != null) check(offer(bytes)) // Uncertain writer admission is terminal, never retried.
                        reply.writeInt(0); reply.writeInt(if (bytes == null) 0 else 1)
                    }
                    OpenFluxHttpIpc.REPLY_STATUS -> {
                        check(data.dataAvail() == 0)
                        val state = request.httpReplyStatus(); check(state in 0..2)
                        reply.writeInt(0); reply.writeInt(state)
                    }
                }
                check(reply.dataSize() <= OpenFluxHttpIpc.MAX_PARCEL)
            } catch (_: Exception) {
                reply.setDataPosition(0); reply.setDataSize(0); reply.writeInt(1)
                if (acquired || code != OpenFluxHttpIpc.ATTACH && callingOwner && tokenOwned) release()
            }
            return true
        }
    }
    companion object {
        private val HEX_ID = Regex("[0-9a-f]{32}")
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    }
}
