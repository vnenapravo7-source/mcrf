package org.mcrf.client

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID
import java.util.function.Consumer

/** Actual Android Parcel/client checks with an explicitly synthetic LOCAL Binder peer.
 * These are overall rejection checks, not independent proofs of each overlapping guard.
 * Does not prove server identity checks, remote transport/death, WebView or cookie isolation.
 * No Activity/service/provider/network, main preferences, real child or clipboard is used. */
object Post55OpenFluxParcelDeviceChecks {
    private class Peer(private val id: String, var snapshot: (Parcel) -> Unit) : Binder() {
        var releases = 0
        var replies = 0
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (reply == null || flags and IBinder.FLAG_ONEWAY != 0) return false
            data.enforceInterface(OpenFluxVerificationClient.DESCRIPTOR)
            check(data.readString() == id)
            when (code) {
                1 -> { check(data.readStrongBinder() != null && data.dataAvail() == 0); reply.writeInt(0); snapshot(reply) }
                2 -> { check(data.dataAvail() == 0); reply.writeInt(0); snapshot(reply) }
                3 -> {
                    check(data.readString() == "cookie=synthetic" && data.dataAvail() == 0)
                    replies++; reply.writeInt(0); reply.writeInt(1)
                }
                4 -> { check(data.readInt() in 0..1 && data.dataAvail() == 0); releases++; reply.writeInt(0) }
                else -> return false
            }
            return true
        }
    }
    private fun snapshot(url: String = "https://captcha.invalid/check", origin: String = "https://captcha.invalid",
                         remaining: Long = 10000): (Parcel) -> Unit = { reply ->
        reply.writeInt(1); reply.writeString(url); reply.writeString(origin); reply.writeLong(remaining); reply.writeInt(0)
    }
    private fun rejected(id: String, write: (Parcel) -> Unit): Boolean {
        var client: OpenFluxVerificationClient? = null
        return try { client = OpenFluxVerificationClient(Peer(id, write), id); false }
        catch (_: Exception) { true }
        finally { client?.close(false) }
    }
    @JvmStatic fun run(base: Context, temporary: File, passed: Consumer<String>) {
        check(base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        val id = UUID.randomUUID().toString()
        val valid = Peer(id, snapshot())
        val client = OpenFluxVerificationClient(valid, id)
        try {
            val request = requireNotNull(client.get(id)); client.poll()
            verify(client.get(id) === request && request.page.origin == "https://captcha.invalid" &&
                client.get(UUID.randomUUID().toString()) == null && !request.submitted.get(),
                "Actual local Android Parcel preserves exact request and cached page identity")
        } finally { client.close(false) }
        check(valid.releases == 1)

        verify(rejected(id) { reply -> snapshot()(reply); reply.writeString("x".repeat(20000)) },
            "Client rejects an oversized local Android reply")
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            verify(rejected(id) { reply -> snapshot()(reply); reply.writeFileDescriptor(pipe[0].fileDescriptor) },
                "Client rejects a real file-descriptor-bearing Android reply")
        } finally { pipe.forEach { it.close() } }
        verify(rejected(id) { reply -> snapshot()(reply); reply.writeInt(12345) },
            "Client rejects trailing Android Parcel data after a valid snapshot")
        verify(rejected(id) { reply ->
            reply.writeInt(1); reply.writeString("https://captcha.invalid/check"); reply.writeString("https://captcha.invalid")
            reply.writeLong(10000) // Missing submitted boolean must not become false.
        }, "Cookie client rejects a truncated snapshot primitive instead of defaulting to zero")
        verify(rejected(id, snapshot(remaining = 0)) &&
            rejected(id, snapshot(origin = "https://different.invalid")) &&
            rejected(id, snapshot(url = "https://127.0.0.1/check", origin = "https://127.0.0.1")),
            "Client rejects expired snapshots, mismatched origins and unsafe page addresses")

        val changing = Peer(id, snapshot())
        val changingClient = OpenFluxVerificationClient(changing, id)
        try {
            changing.snapshot = snapshot(url = "https://captcha.invalid/replaced")
            changingClient.poll()
            verify(changingClient.get(id) == null,
                "Changed page behind the same UI ID invalidates the local client")
        } finally { changingClient.close(false) }

        val accepting = Peer(id, snapshot())
        val acceptingClient = OpenFluxVerificationClient(accepting, id)
        try {
            val request = requireNotNull(acceptingClient.get(id))
            verify(request.submit("cookie=synthetic") && !request.submit("cookie=synthetic") &&
                request.submitted.get() && accepting.replies == 1,
                "Actual local reply Parcel is admitted once and never resubmitted after acceptance")
        } finally { acceptingClient.close(false) }
    }
}
