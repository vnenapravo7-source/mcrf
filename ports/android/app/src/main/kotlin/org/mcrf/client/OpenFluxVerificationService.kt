package org.mcrf.client

import android.app.Service
import android.content.Intent
import android.os.*
import java.util.concurrent.atomic.AtomicBoolean

/** Same-UID, nonexported bridge from the isolated WebView process to the exact main-process request. */
class OpenFluxVerificationService : Service() {
    private val endpoints = mutableMapOf<String, Endpoint>()
    private val registry get() = OpenFluxVerificationRequests.registry
    override fun onBind(intent: Intent): IBinder? {
        val id = intent.getStringExtra("request").orEmpty()
        val key = intent.dataString ?: return null
        if (!id.matches(OpenFluxVerificationClient.ID) || key.length > 160) return null
        if (registry.get(id)?.http == true) return null // HTTP requires a distinct future descriptor/endpoint.
        return synchronized(endpoints) {
            if (endpoints.size >= 32 || endpoints.containsKey(key)) null
            else Endpoint(id, registry.get(id)?.inline == true).also { endpoints[key] = it }
        }
    }
    override fun onUnbind(intent: Intent): Boolean {
        synchronized(endpoints) { endpoints.remove(intent.dataString) }?.release(false)
        return false
    }
    override fun onDestroy() {
        val retained = synchronized(endpoints) { endpoints.values.toList().also { endpoints.clear() } }
        retained.forEach { it.release(false) }; super.onDestroy()
    }
    private inner class Endpoint(private val id: String, private val inline: Boolean) : Binder() {
        private val descriptor get() = if (inline) OpenFluxInlineVerificationClient.DESCRIPTOR else OpenFluxVerificationClient.DESCRIPTOR
        private val closed = AtomicBoolean(false)
        private var owns = false
        private var peer: IBinder? = null
        private var pid = 0
        private val death = IBinder.DeathRecipient { release(false) }

        @Synchronized fun release(keep: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { peer?.unlinkToDeath(death, 0) }; peer = null
            if (owns) {
                if (!keep && registry.get(id)?.submitted?.get() != true) registry.cancel(id)
                registry.releaseBrowser(id)
                val reopen = keep && registry.reopenAfterRecreate(id)
                Handler(Looper.getMainLooper()).post {
                    if (reopen && registry.get(id)?.opened?.get() == false) OpenFluxVerificationUi.show(this@OpenFluxVerificationService, id)
                    else OpenFluxVerificationUi.cancel(this@OpenFluxVerificationService, id)
                }
            } else if (registry.reopenAfterRecreate(id)) {
                // A failed pre-ATTACH opening must not leave MainActivity's opened flag stuck.
                // A different endpoint's existing browser ownership is protected by registry.
                Handler(Looper.getMainLooper()).post {
                    if (registry.get(id)?.opened?.get() == false) OpenFluxVerificationUi.show(this@OpenFluxVerificationService, id)
                }
            }
            owns = false
        }
        @Synchronized override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Check identity and bounds BEFORE decoding strings or Binder objects.
            if (Binder.getCallingUid() != android.os.Process.myUid() || Binder.getCallingPid() == android.os.Process.myPid() || data.dataSize() > 32768 || data.hasFileDescriptors()) return false
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(descriptor); return true }
            if (code !in 1..(if (inline) 6 else 4) || flags and IBinder.FLAG_ONEWAY != 0 || reply == null) return false
            var acquiredHere = false
            try {
                data.enforceInterface(descriptor)
                check(data.readString() == id && !closed.get())
                if (code != 1) check(owns && pid == Binder.getCallingPid())
                when (code) {
                    1 -> {
                        check(!owns)
                        val token = requireNotNull(data.readStrongBinder()); check(data.dataAvail() == 0)
                        check(registry.get(id)?.inline == inline) // Descriptor fixed to the exact request kind at bind.
                        if (!registry.claimBrowser(id)) {
                            if (registry.reopenAfterRecreate(id)) Handler(Looper.getMainLooper()).post {
                                if (registry.get(id)?.opened?.get() == false) OpenFluxVerificationUi.show(this@OpenFluxVerificationService, id)
                            }
                            error("OpenFlux browser unavailable")
                        }
                        owns = true; acquiredHere = true; pid = Binder.getCallingPid()
                        peer = token; token.linkToDeath(death, 0)
                        reply.writeInt(0); snapshot(reply)
                        Handler(Looper.getMainLooper()).post {
                            if (registry.browserOwnedBy(id)) OpenFluxVerificationUi.cancel(this@OpenFluxVerificationService, id)
                        }
                    }
                    2 -> { check(data.dataAvail() == 0); reply.writeInt(0); snapshot(reply) }
                    3 -> {
                        val cookies = requireNotNull(data.readString()); check(cookies.length <= 8192 && data.dataAvail() == 0)
                        val request = registry.get(id)
                        val accepted = if (inline) request?.submitOwn(cookies) == true else request?.submit(cookies) == true
                        reply.writeInt(0); reply.writeInt(if (accepted) 1 else 0)
                    }
                    4 -> {
                        val keep = OpenFluxParcel.readInt(data); check(keep in 0..1 && data.dataAvail() == 0)
                        release(keep == 1 && !inline); reply.writeInt(0) // No own-page read replay after recreation.
                    }
                    5 -> {
                        check(inline && data.dataAvail() == 0)
                        val accepted = registry.get(id)?.requestDocumentAfterUserOpen() == true
                        reply.writeInt(0); reply.writeInt(if (accepted) 1 else 0)
                    }
                    6 -> {
                        check(inline)
                        val offset = OpenFluxParcel.readInt(data); check(data.dataAvail() == 0)
                        val result = registry.get(id)?.pullDocument(offset)
                        reply.writeInt(0)
                        val chunk = result?.chunk
                        if (chunk == null) reply.writeInt(if (result?.waiting == true) 0 else -1)
                        else try {
                            reply.writeInt(1); reply.writeInt(chunk.total); reply.writeString(chunk.sha256)
                            reply.writeInt(chunk.offset); reply.writeByteArray(chunk.bytes)
                        } finally { chunk.close() }
                    }
                }
            } catch (_: Exception) {
                // Fixed error only, never echo exception/URL/cookies into IPC diagnostics.
                reply.setDataPosition(0); reply.setDataSize(0); reply.writeInt(1)
                if (code == 1 && acquiredHere) release(false) // A duplicate/malformed attach cannot retire an earlier owner.
            }
            return true
        }
        private fun snapshot(reply: Parcel) {
            val request = registry.get(id)?.takeIf { it.inline == inline }
            reply.writeInt(if (request == null) 0 else 1)
            if (request != null) {
                if (!inline) { reply.writeString(request.page.url); reply.writeString(request.page.origin) }
                reply.writeLong(request.remainingMillis()); reply.writeInt(if (request.submitted.get()) 1 else 0)
            }
        }
    }
}

/** Set before any WebView/CookieManager in the dedicated process; no silent fallback. */
internal object OpenFluxVerificationWebProfile { var ready = false }
