package org.mcrf.client

import android.content.Context
import android.os.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.function.Consumer

/** Actual Android Parcel/client code with a synthetic LOCAL Binder only.
 * Not remote identity/death, service ownership, Activity, WebView or gesture proof.
 * No real child, provider, profile cookies, main settings or VPN is touched. */
object Post55OpenFluxInlineParcelDeviceChecks {
    private fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private class Peer(val id: String, val raw: ByteArray = "synthetic".toByteArray()) : Binder() {
        var descriptor = OpenFluxInlineVerificationClient.DESCRIPTOR
        var snapshot: ((Parcel) -> Unit)? = null
        var chunk: ((Int, Parcel) -> Unit)? = null
        var opens = 0; var pulls = 0; var replies = 0; var releases = 0
        var acceptOpen = true; var sent = false
        fun normalSnapshot(out: Parcel) { out.writeInt(1); out.writeLong(120000); out.writeInt(if (sent) 1 else 0) }
        fun normalChunk(offset: Int, out: Parcel) {
            check(offset in raw.indices && offset % 8192 == 0)
            out.writeInt(1); out.writeInt(raw.size); out.writeString(Post55OpenFluxInlineParcelDeviceChecks.sha(raw)); out.writeInt(offset)
            val copy = raw.copyOfRange(offset, minOf(offset + 8192, raw.size))
            try { out.writeByteArray(copy) } finally { copy.fill(0) }
        }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (reply == null || flags and IBinder.FLAG_ONEWAY != 0) return false
            data.enforceInterface(descriptor); check(data.readString() == id)
            when (code) {
                1 -> {
                    check(data.readStrongBinder() != null && data.dataAvail() == 0); reply.writeInt(0)
                    snapshot?.invoke(reply) ?: normalSnapshot(reply)
                }
                2 -> { check(data.dataAvail() == 0); reply.writeInt(0); snapshot?.invoke(reply) ?: normalSnapshot(reply) }
                3 -> {
                    check(data.readString() == "{\"answer\":\"синтетический\"}" && data.dataAvail() == 0)
                    replies++; sent = true; reply.writeInt(0); reply.writeInt(1)
                }
                4 -> { check(data.readInt() == 0 && data.dataAvail() == 0); releases++; reply.writeInt(0) }
                5 -> { check(data.dataAvail() == 0); opens++; reply.writeInt(0); reply.writeInt(if (acceptOpen) 1 else 0) }
                6 -> {
                    val offset = data.readInt(); check(data.dataAvail() == 0); pulls++; reply.writeInt(0)
                    chunk?.invoke(offset, reply) ?: normalChunk(offset, reply)
                }
                else -> return false
            }
            return true
        }
    }
    private fun attachRejected(peer: Peer, id: String = peer.id): Boolean {
        var client: OpenFluxInlineVerificationClient? = null
        return try { client = OpenFluxInlineVerificationClient(peer, id); false }
        catch (_: Exception) { true } finally { client?.close() }
    }
    private fun pullRejected(peer: Peer, afterFirst: Boolean = false): Boolean {
        val client = OpenFluxInlineVerificationClient(peer, peer.id)
        return try {
            check(client.beginAfterUserOpen())
            if (afterFirst) check(client.pull().progress)
            val result = client.pull()
            result.html == null && !result.waiting && !client.current() && !client.submitAfterUserAction("{\"answer\":\"синтетический\"}")
        } finally { client.close() }
    }
    @JvmStatic fun run(base: Context, temporary: File, passed: Consumer<String>) {
        check(base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        val id = UUID.randomUUID().toString()
        val payload = "{\"answer\":\"синтетический\"}"
        val html = "x".repeat(8191) + "я<html>synthetic</html>"
        val good = Peer(id, html.toByteArray(Charsets.UTF_8)); val client = OpenFluxInlineVerificationClient(good, id)
        var complete = false
        try {
            check(!client.submitAfterUserAction(payload) && client.beginAfterUserOpen() && !client.beginAfterUserOpen())
            val first = client.pull(); check(first.html == null && first.progress && first.waiting)
            check(!client.submitAfterUserAction(payload))
            val last = client.pull(); client.poll()
            complete = last.html == html && !last.waiting && client.submitAfterUserAction(payload) &&
                !client.submitAfterUserAction(payload) && good.replies == 1 && good.opens == 1 && good.pulls == 2
        } finally { client.close() }
        verify(complete && good.releases == 1, "Own local Parcel completes split UTF8/hash before one explicit reply and exact release")

        verify(attachRejected(Peer(id).apply { descriptor = OpenFluxVerificationClient.DESCRIPTOR }) &&
            attachRejected(Peer(id), UUID.randomUUID().toString()), "Own client rejects cookie descriptor and different opaque ID on local peer")
        verify(attachRejected(Peer(id).apply { snapshot = { out -> normalSnapshot(out); out.writeString("x".repeat(20000)) } }),
            "Own client rejects oversized actual Android snapshot Parcel")
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            verify(attachRejected(Peer(id).apply { snapshot = { out -> normalSnapshot(out); out.writeFileDescriptor(pipe[0].fileDescriptor) } }),
                "Own client rejects file-descriptor-bearing snapshot without consuming it")
        } finally { pipe.forEach { it.close() } }
        verify(attachRejected(Peer(id).apply { snapshot = { out -> normalSnapshot(out); out.writeInt(9) } }),
            "Own client rejects trailing data after snapshot")
        verify(listOf(0L, 150001L).all { remaining -> attachRejected(Peer(id).apply {
            snapshot = { out -> out.writeInt(1); out.writeLong(remaining); out.writeInt(0) }
        }) } && attachRejected(Peer(id).apply { snapshot = { out -> out.writeInt(2) } }),
            "Own client rejects invalid lifetime and presence states")

        val denied = Peer(id).apply { acceptOpen = false }; val deniedClient = OpenFluxInlineVerificationClient(denied, id)
        try {
            verify(!deniedClient.beginAfterUserOpen() && !deniedClient.beginAfterUserOpen() &&
                deniedClient.pull().html == null && denied.opens == 1 && denied.pulls == 0,
                "Rejected own OPEN never retries or begins pulling")
        } finally { deniedClient.close() }

        verify(listOf(-1, 0, 8193, Int.MAX_VALUE, 8192).all { length ->
            pullRejected(Peer(id).apply { chunk = { offset, out ->
                out.writeInt(1); out.writeInt(9000); out.writeString("0".repeat(64)); out.writeInt(offset)
                out.writeInt(length) // Deliberately no array payload, including truncated valid-sized declaration.
            } })
        }, "Own client rejects malicious array declarations before unbounded allocation")

        verify((0..3).all { mode ->
            val peer = Peer(id, ByteArray(9000) { 120 })
            peer.chunk = { offset, out ->
                if (offset == 0) peer.normalChunk(offset, out)
                else if (mode == 3) out.writeInt(2)
                else {
                    out.writeInt(1); out.writeInt(if (mode == 0) 9001 else 9000)
                    out.writeString(if (mode == 1) "0".repeat(64) else sha(peer.raw))
                    out.writeInt(if (mode == 2) 0 else offset); out.writeByteArray(peer.raw.copyOfRange(offset, peer.raw.size))
                }
            }
            pullRejected(peer, afterFirst = true)
        }, "Own partial client rejects changed total/hash/offset and unknown pull state")

        verify(pullRejected(Peer(id).apply { chunk = { offset, out ->
            out.writeInt(1); out.writeInt(raw.size); out.writeString("0".repeat(64)); out.writeInt(offset); out.writeByteArray(raw)
        } }) && pullRejected(Peer(id, byteArrayOf(0xff.toByte()))),
            "Own client never exposes completed bad-digest or invalid-UTF8 HTML")

        val failing = Peer(id, ByteArray(9000) { 120 }).apply { chunk = { offset, out ->
            if (offset == 0) normalChunk(offset, out) else throw DeadObjectException()
        } }
        verify(pullRejected(failing, afterFirst = true),
            "Synthetic local transaction failure invalidates partial own client; not actual Binder death proof")

        val chunkPipe = ParcelFileDescriptor.createPipe()
        try {
            verify(pullRejected(Peer(id).apply { chunk = { offset, out -> normalChunk(offset, out); out.writeInt(7) } }) &&
                pullRejected(Peer(id).apply { chunk = { offset, out -> normalChunk(offset, out); out.writeFileDescriptor(chunkPipe[0].fileDescriptor) } }),
                "Own chunk reply rejects trailing bytes or file descriptors before HTML exposure")
        } finally { chunkPipe.forEach { it.close() } }
        verify(attachRejected(Peer(id).apply { snapshot = { out -> out.writeInt(1); out.writeLong(120000) } }) &&
            pullRejected(Peer(id).apply { chunk = { _, _ -> Unit } }),
            "Missing snapshot boolean or pull state is rejected, not interpreted as zero or WAIT")
    }
}
