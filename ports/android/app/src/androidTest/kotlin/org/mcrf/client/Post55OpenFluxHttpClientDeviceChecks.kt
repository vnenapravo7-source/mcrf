package org.mcrf.client

import android.app.Instrumentation
import android.os.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.function.Consumer

/** NOTRUN. Real Android Parcel/Looper and production client, synthetic LOCAL
 * Binder only. Not kernel UID/PID, service/native child, real death or UI proof. */
object Post55OpenFluxHttpClientDeviceChecks {
    private const val ADDRESS = "http://127.0.0.1:4321/check"
    private val REQUEST = "e".repeat(32)
    private val UPLOAD = "f".repeat(32)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private class Peer(val id: String, val body: ByteArray = byteArrayOf()) : Binder() {
        val calls = mutableMapOf<Int, Int>()
        var token: IBinder? = null
        var opened = false
        var snapshot: ((Parcel) -> Unit)? = null
        var fault: ((Int, Parcel) -> Boolean?)? = null
        var digest = hash(body)
        var status = 200
        var method = "GET"
        var received = 0
        var replyState = 1
        var expectedUpload = byteArrayOf()
        fun count(code: Int) = calls[code] ?: 0
        fun normalSnapshot(out: Parcel) {
            out.writeInt(if (opened) 2 else 0); out.writeLong(120000); out.writeString(if (opened) ADDRESS else "")
        }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            check(reply != null && flags == 0)
            data.enforceInterface(OpenFluxHttpIpc.DESCRIPTOR); check(data.readString() == id)
            val owner = requireNotNull(data.readStrongBinder())
            if (code == OpenFluxHttpIpc.ATTACH) { check(token == null); token = owner } else check(owner == token)
            calls[code] = count(code) + 1
            fault?.invoke(code, reply)?.let { return it }
            reply.writeInt(0)
            when (code) {
                OpenFluxHttpIpc.ATTACH, OpenFluxHttpIpc.POLL -> { snapshot?.invoke(reply) ?: normalSnapshot(reply) }
                OpenFluxHttpIpc.FOREGROUND -> { check(OpenFluxParcel.readInt(data) in 0..1) }
                OpenFluxHttpIpc.OPEN -> { check(!opened); opened = true }
                OpenFluxHttpIpc.BEGIN -> {
                    method = requireNotNull(data.readString()); check(data.readString() == ADDRESS && data.readString() == "[]")
                    check(OpenFluxParcel.readInt(data) == expectedUpload.size); received = 0; reply.writeString(UPLOAD)
                }
                OpenFluxHttpIpc.PUSH -> {
                    check(data.readString() == UPLOAD && OpenFluxParcel.readInt(data) == received)
                    val bytes = OpenFluxParcel.readBytes(data, OpenFluxHttpIpc.CHUNK)
                    try {
                        check(bytes.contentEquals(expectedUpload.copyOfRange(received, received + bytes.size)))
                        received += bytes.size
                    } finally { bytes.fill(0) }
                }
                OpenFluxHttpIpc.COMMIT -> {
                    check(data.readString() == UPLOAD && received == expectedUpload.size)
                    reply.writeInt(1); reply.writeString(REQUEST)
                }
                OpenFluxHttpIpc.METADATA -> {
                    check(data.readString() == REQUEST); reply.writeInt(1); reply.writeString(REQUEST)
                    reply.writeInt(status); reply.writeString("[]"); reply.writeInt(body.size); reply.writeString(digest)
                }
                OpenFluxHttpIpc.PULL -> {
                    check(data.readString() == REQUEST); val offset = OpenFluxParcel.readInt(data)
                    check(offset in 0..body.size && offset % 8192 == 0)
                    val end = minOf(offset + 8192, body.size)
                    reply.writeInt(offset); reply.writeInt(if (end == body.size) 1 else 0)
                    val copy = body.copyOfRange(offset, end)
                    try { reply.writeByteArray(copy) } finally { copy.fill(0) }
                }
                OpenFluxHttpIpc.RELEASE -> Unit
                OpenFluxHttpIpc.REPLY -> {
                    check(OpenFluxOwnPageSession.admissible(requireNotNull(data.readString())))
                    reply.writeInt(1)
                }
                OpenFluxHttpIpc.REPLY_STATUS -> { reply.writeInt(replyState) }
                else -> error("unexpected opcode")
            }
            check(data.dataAvail() == 0)
            return true
        }
    }
    private fun attachRejected(peer: Peer): Boolean {
        var client: OpenFluxHttpVerificationClient? = null
        return try { client = OpenFluxHttpVerificationClient(peer, peer.id) { true }; false }
        catch (_: java.io.IOException) { true } finally { client?.close() }
    }
    private fun ready(peer: Peer): OpenFluxHttpVerificationClient {
        val client = OpenFluxHttpVerificationClient(peer, peer.id) { true }
        try { check(client.foreground(true) && client.openAfterUserAction() && client.poll()?.code == 2); return client }
        catch (failure: Throwable) { client.close(); throw failure }
    }
    private fun transfer(peer: Peer, client: OpenFluxHttpVerificationClient, method: String = "GET"): Boolean {
        val request = client.enqueue(method, ADDRESS, emptyList(), peer.expectedUpload) ?: return false
        for (index in 0..64) {
            val pull = client.pull(request)
            pull.response?.let { response ->
                return try { !pull.waiting && response.body.contentEquals(peer.body) }
                finally { response.close() }
            }
            if (!pull.waiting) return false
        }
        return false
    }
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base = instrumentation.targetContext
        check(BuildConfig.DEBUG && base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(Looper.myLooper() != Looper.getMainLooper() && !RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        fun verify(ok: Boolean, name: String) { check(ok) { name }; passed.accept(name) }
        fun peer(body: ByteArray = byteArrayOf()) = Peer(UUID.randomUUID().toString(), body)
        val mainPeer = peer(); var refused = false
        instrumentation.runOnMainSync {
            try { OpenFluxHttpVerificationClient(mainPeer, mainPeer.id) { true }.close() }
            catch (_: IllegalStateException) { refused = true }
        }
        verify(refused && mainPeer.calls.isEmpty(), "HTTP main Looper construction rejects before any Binder transaction")

        val large = peer(ByteArray(524288) { (it * 37).toByte() }); val largeClient = ready(large)
        try {
            var mainCallRefused = false; val before = large.calls.toMap()
            instrumentation.runOnMainSync { try { largeClient.poll() } catch (_: IllegalStateException) { mainCallRefused = true } }
            verify(mainCallRefused && large.calls == before && largeClient.current(),
                "HTTP main Looper method rejects before transaction without retiring worker-owned client")
            verify(transfer(large, largeClient) && large.count(OpenFluxHttpIpc.PULL) == 64,
                "HTTP local Parcel transfers exact binary512KiB in64 chunks with complete hash before ownership transfer")
        } finally { largeClient.close(); large.body.fill(0) }
        val empty = peer(); val emptyClient = ready(empty)
        try { verify(transfer(empty, emptyClient) && empty.count(OpenFluxHttpIpc.PULL) == 1,
            "HTTP empty response requires one empty terminal Parcel chunk") } finally { emptyClient.close() }

        verify(attachRejected(peer().apply { snapshot = { it.writeInt(0) } }), "HTTP truncated snapshot primitives reject without partial publication")
        verify(attachRejected(peer().apply { snapshot = { normalSnapshot(it); it.writeInt(9) } }), "HTTP trailing snapshot rejects complete reply")
        verify(attachRejected(peer().apply { snapshot = { normalSnapshot(it); it.writeString("x".repeat(20000)) } }),
            "HTTP oversized snapshot Parcel rejects before state publication")
        val pipe = ParcelFileDescriptor.createPipe()
        try { verify(attachRejected(peer().apply { snapshot = { normalSnapshot(it); it.writeFileDescriptor(pipe[0].fileDescriptor) } }),
            "HTTP FD-bearing snapshot rejects without consuming descriptor") } finally { pipe.forEach { it.close() } }
        verify(listOf(0L, 150001L).all { ttl -> attachRejected(peer().apply {
            snapshot = { it.writeInt(0); it.writeLong(ttl); it.writeString("") }
        }) }, "HTTP invalid snapshot lifetime cannot renew or initialize client")
        for (dead in listOf(false, true)) {
            val denied = peer().apply { fault = { code, _ ->
                if (code == OpenFluxHttpIpc.OPEN) { if (dead) throw DeadObjectException(); false } else null
            } }
            val client = OpenFluxHttpVerificationClient(denied, denied.id) { true }
            try {
                verify(!client.openAfterUserAction() && !client.openAfterUserAction() && denied.count(OpenFluxHttpIpc.OPEN) == 1 && !client.current(),
                    "HTTP uncertain OPEN ${if (dead) "DeadObject" else "false"} is terminal and never replayed")
            } finally { client.close() }
        }
        val commit = peer(); val commitClient = ready(commit)
        try {
            commit.fault = { code, _ -> if (code == OpenFluxHttpIpc.COMMIT) false else null }
            verify(commitClient.enqueue("GET", ADDRESS, emptyList(), byteArrayOf()) == null &&
                commitClient.enqueue("GET", ADDRESS, emptyList(), byteArrayOf()) == null && commit.count(OpenFluxHttpIpc.COMMIT) == 1,
                "HTTP uncertain COMMIT is not retried or exposed as accepted request")
        } finally { commitClient.close() }
        val badHash = peer(byteArrayOf(0, -1, 1)).apply { digest = "0".repeat(64) }; val hashClient = ready(badHash)
        try { verify(!transfer(badHash, hashClient) && !hashClient.current(),
            "HTTP complete binary response with wrong digest is never transferred") } finally { hashClient.close(); badHash.body.fill(0) }
        val trailing = peer(byteArrayOf(1)); val trailingClient = ready(trailing)
        try {
            trailing.fault = { code, out -> if (code == OpenFluxHttpIpc.PULL) {
                out.writeInt(0); out.writeInt(0); out.writeInt(1); out.writeByteArray(byteArrayOf(1)); out.writeInt(9); true
            } else null }
            verify(!transfer(trailing, trailingClient) && !trailingClient.current(),
                "HTTP trailing terminal PULL cannot transfer an otherwise complete matching response")
        } finally { trailingClient.close(); trailing.body.fill(0) }
        val moved = peer(); val movedClient = ready(moved)
        try {
            moved.snapshot = { it.writeInt(2); it.writeLong(120000); it.writeString("http://127.0.0.1:4322/check") }
            verify(movedClient.poll() == null && !movedClient.current() && movedClient.snapshot() == null,
                "HTTP ready origin change is terminal rather than published as replacement snapshot")
        } finally { movedClient.close() }
        val noBody = peer(byteArrayOf(1)).apply { status = 204 }; val noBodyClient = ready(noBody)
        try {
            check(noBodyClient.enqueue("GET", ADDRESS, emptyList(), byteArrayOf()) == REQUEST)
            val result = noBodyClient.pull(REQUEST)
            verify(result.response == null && !result.waiting && !noBodyClient.current() && noBody.count(OpenFluxHttpIpc.PULL) == 0,
                "HTTP204 nonempty metadata refuses before allocating or pulling forbidden body")
        } finally { noBodyClient.close(); noBody.body.fill(0) }
        val upload = peer().apply { expectedUpload = ByteArray(65536) { (it * 13).toByte() } }
        val uploadClient = ready(upload)
        try {
            // Initial navigation must stay empty GET; its caller payload may not bypass the gate.
            check(uploadClient.enqueue("POST", ADDRESS, emptyList(), upload.expectedUpload) == null && upload.count(OpenFluxHttpIpc.BEGIN) == 0)
            upload.expectedUpload.fill(0); upload.expectedUpload = byteArrayOf()
            check(transfer(upload, uploadClient))
            // Different accepted IDs are mandatory even on a synthetic peer.
            // This source fixture deliberately limits the subsequent POST to admission/PUSH validation.
            upload.expectedUpload = ByteArray(65536) { (it * 13).toByte() }
            upload.fault = { code, out -> if (code == OpenFluxHttpIpc.COMMIT) {
                out.writeInt(0); out.writeInt(0); true
            } else null }
            verify(uploadClient.enqueue("POST", ADDRESS, emptyList(), upload.expectedUpload) == null &&
                upload.received == 65536 && upload.count(OpenFluxHttpIpc.PUSH) == 8 && uploadClient.current(),
                "HTTP initial GET gate and exact64KiB eight-chunk upload preserve explicit COMMIT refusal")
        } finally { uploadClient.close(); upload.expectedUpload.fill(0) }
        val signalled = peer(); val signalledClient = ready(signalled)
        try {
            val before = signalled.calls.toMap(); signalledClient.signalClose()
            check(!signalledClient.current() && signalledClient.snapshot() == null)
            signalledClient.close()
            verify(signalled.calls.filterKeys { it != OpenFluxHttpIpc.RELEASE } == before && signalled.count(OpenFluxHttpIpc.RELEASE) == 1,
                "HTTP signalClose denies future admission and worker cleanup sends one terminal release")
        } finally { signalledClient.close() }
        val send = peer(); val sendClient = ready(send)
        try {
            check(!sendClient.submitAfterUserAction("{\"answer\":true}") && send.count(OpenFluxHttpIpc.REPLY) == 0)
            check(transfer(send, sendClient))
            verify(sendClient.submitAfterUserAction("{\"answer\":true}") && sendClient.replyStatus() == 1 &&
                !sendClient.submitAfterUserAction("{\"answer\":false}") && send.count(OpenFluxHttpIpc.REPLY) == 1 &&
                sendClient.enqueue("GET", ADDRESS, emptyList(), byteArrayOf()) == null,
                "HTTP explicit Send requires initial transfer, is one-shot and command admission is not native acceptance")
            send.replyState = 2
            verify(sendClient.replyStatus() == 2 && send.count(OpenFluxHttpIpc.REPLY) == 1,
                "HTTP separate reply status accepts only explicit peer ACK state, not REPLY transaction success")
        } finally { sendClient.close() }
        val uncertain = peer(); val uncertainClient = ready(uncertain)
        try {
            check(transfer(uncertain, uncertainClient))
            uncertain.fault = { code, _ -> if (code == OpenFluxHttpIpc.REPLY) false else null }
            verify(!uncertainClient.submitAfterUserAction("{\"answer\":true}") &&
                !uncertainClient.submitAfterUserAction("{\"answer\":true}") && uncertain.count(OpenFluxHttpIpc.REPLY) == 1 &&
                uncertainClient.replyStatus() == -1,
                "HTTP uncertain Send is terminal and never replayed or reported as accepted")
        } finally { uncertainClient.close() }
        fun bytesRejected(write: (Parcel) -> Unit): Boolean {
            val parcel = Parcel.obtain()
            try {
                write(parcel); parcel.setDataPosition(0)
                return try { OpenFluxParcel.readBytes(parcel, 8192).also { it.fill(0) }; false } catch (_: Exception) { true }
            } finally { parcel.recycle() }
        }
        verify(bytesRejected { } && bytesRejected { it.writeInt(-1) } && bytesRejected { it.writeInt(8193) } &&
            bytesRejected { it.writeInt(1) } && bytesRejected { it.writeInt(8); it.writeInt(123) },
            "HTTP embedded bytearray length and actual padded payload bounds reject before allocation")
    }
}
