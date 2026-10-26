package org.mcrf.client

import android.app.Service
import android.content.Intent
import android.os.*

/** DEBUG source set only. Bounded synthetic checks against an explicitly supplied
 * private endpoint; no Activity/WebView/native/provider or general command API. */
open class OpenFluxReviewPeerService : Service() {
    protected open val foreign = false
    override fun onBind(intent: Intent): IBinder? {
        if (!BuildConfig.DEBUG || packageName != "org.mcrf.p3test") return null
        return object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code !in 1..5 || reply == null || flags != 0 || data.dataSize() > 1024 || data.hasFileDescriptors() ||
                    Binder.getCallingUid() != applicationInfo.uid || Binder.getCallingPid() == Process.myPid()) return false
                try {
                    data.enforceInterface(DESCRIPTOR)
                    val target = requireNotNull(data.readStrongBinder())
                    val id = requireNotNull(data.readString())
                    val scenario = if (code == 4) OpenFluxParcel.readInt(data).also { check(it in 0..16) } else 0
                    val rendezvous = if (code == 5) requireNotNull(data.readStrongBinder()) else null
                    check(id.matches(OpenFluxVerificationClient.ID) && data.dataAvail() == 0)
                    val mask = if (foreign) {
                        check(Process.myUid() != applicationInfo.uid)
                        val input = Parcel.obtain(); val output = Parcel.obtain()
                        try {
                            input.writeInt(123) // Actual foreign UID must fail before decoding.
                            check(!target.transact(1, input, output, 0) && output.dataSize() == 0)
                        } finally { input.recycle(); output.recycle() }
                        1
                    } else {
                        check(Process.myUid() == applicationInfo.uid)
                        when (code) { 5 -> httpUnbindSuite(target, id, requireNotNull(rendezvous)); 4 -> httpMalformedSuite(target, id, scenario); 3 -> httpTransferSuite(target, id); 2 -> httpSuite(target, id); else -> ownSuite(target, id) }
                    }
                    reply.writeInt(0); reply.writeInt(Process.myPid()); reply.writeInt(Process.myUid()); reply.writeInt(mask)
                } catch (_: Exception) {
                    reply.setDataPosition(0); reply.setDataSize(0); reply.writeInt(1) // No target payload/exception echo.
                }
                return true
            }
        }
    }
    private fun ownSuite(target: IBinder, id: String): Int {
        val peer = Binder()
        val descriptor = OpenFluxInlineVerificationClient.DESCRIPTOR
        var mask = 0
        fun call(code: Int, token: String = descriptor, request: String = id,
                 write: (Parcel) -> Unit = {}, read: (Parcel) -> Unit) {
            val input = Parcel.obtain(); val output = Parcel.obtain()
            try {
                input.writeInterfaceToken(token); input.writeString(request); write(input)
                check(target.transact(code, input, output, 0)); read(output); check(output.dataAvail() == 0)
            } finally { input.recycle(); output.recycle() }
        }
        fun rejected(code: Int, token: String = descriptor, request: String = id, write: (Parcel) -> Unit = {}) =
            call(code, token, request, write) { check(OpenFluxParcel.readInt(it) == 1) }
        fun success(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> Unit = {}) =
            call(code, write = write) { check(OpenFluxParcel.readInt(it) == 0); read(it) }
        fun snapshot(out: Parcel) {
            check(OpenFluxParcel.readInt(out) == 1)
            check(OpenFluxParcel.readLong(out) in 1..150000); check(OpenFluxParcel.readInt(out) == 0)
        }
        fun proved(bit: Int, checkCase: () -> Unit) { checkCase(); mask = mask or (1 shl bit) }
        proved(0) {
            rejected(1, token = OpenFluxVerificationClient.DESCRIPTOR)
            rejected(1, request = java.util.UUID.randomUUID().toString())
        }
        proved(1) {
            rejected(1)
            rejected(1, write = { it.writeStrongBinder(peer); it.writeInt(123) })
        }
        proved(2) { success(1, { it.writeStrongBinder(peer) }, ::snapshot) }
        proved(3) { rejected(1, write = { it.writeStrongBinder(peer) }); success(2, read = ::snapshot) }
        proved(4) {
            rejected(2, write = { it.writeInt(123) })
            rejected(3, write = { it.writeString(null) })
            rejected(3, write = { it.writeString("{\"synthetic\":true}"); it.writeInt(123) })
            success(2, read = ::snapshot)
        }
        proved(5) {
            rejected(4); rejected(4, write = { it.writeInt(2) }); success(2, read = ::snapshot)
        }
        proved(6) {
            rejected(6)
            success(6, { it.writeInt(-1) }) { check(OpenFluxParcel.readInt(it) == -1) }
            success(2, read = ::snapshot)
        }
        proved(7) {
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                for (fd in listOf(false, true)) {
                    val input = Parcel.obtain(); val output = Parcel.obtain()
                    try {
                        input.writeInterfaceToken(descriptor); input.writeString(id)
                        if (fd) input.writeFileDescriptor(pipe[0].fileDescriptor) else input.writeByteArray(ByteArray(40000))
                        check(!target.transact(2, input, output, 0) && output.dataSize() == 0)
                    } finally { input.recycle(); output.recycle() }
                }
            } finally { pipe.forEach { it.close() } }
            success(2, read = ::snapshot)
        }
        proved(8) {
            success(5) { check(OpenFluxParcel.readInt(it) == 1) }
            success(5) { check(OpenFluxParcel.readInt(it) == 0) }
            success(6, { it.writeInt(0) }) { check(OpenFluxParcel.readInt(it) == 0) }
        }
        proved(9) { success(4, { it.writeInt(0) }); rejected(2) }
        proved(10) { rejected(1, write = { it.writeStrongBinder(peer) }); rejected(4, write = { it.writeInt(0) }) }
        return mask
    }
    /** Fixed HTTP ownership-only suite. Never OPENs a native ticket or serves HTML. */
    private fun httpSuite(target: IBinder, id: String): Int {
        val peer = Binder()
        var mask = 0
        fun call(code: Int, owner: IBinder = peer, descriptor: String = OpenFluxHttpIpc.DESCRIPTOR,
                 write: (Parcel) -> Unit = {}, read: (Parcel) -> Unit) {
            val input = Parcel.obtain(); val output = Parcel.obtain()
            try {
                input.writeInterfaceToken(descriptor); input.writeString(id)
                input.writeStrongBinder(owner); write(input)
                check(target.transact(code, input, output, 0))
                check(!output.hasFileDescriptors() && output.dataSize() <= OpenFluxHttpIpc.MAX_PARCEL)
                read(output); check(output.dataAvail() == 0)
            } finally { input.recycle(); output.recycle() }
        }
        fun reject(code: Int, owner: IBinder = peer, descriptor: String = OpenFluxHttpIpc.DESCRIPTOR) =
            call(code, owner, descriptor) { check(OpenFluxParcel.readInt(it) == 1) }
        fun snapshot(out: Parcel) {
            check(OpenFluxParcel.readInt(out) == 0)
            check(OpenFluxParcel.readInt(out) == 0)
            check(OpenFluxParcel.readLong(out) in 1..150000)
            check(out.readString() == "")
        }
        fun proved(bit: Int, action: () -> Unit) { action(); mask = mask or (1 shl bit) }
        proved(0) { reject(OpenFluxHttpIpc.ATTACH, descriptor = OpenFluxInlineVerificationClient.DESCRIPTOR) }
        proved(1) { call(OpenFluxHttpIpc.ATTACH, read = ::snapshot) }
        proved(2) {
            reject(OpenFluxHttpIpc.ATTACH)
            reject(OpenFluxHttpIpc.RELEASE, owner = Binder())
            reject(OpenFluxHttpIpc.POLL, owner = Binder())
            // Actual client constructor sends its terminal RELEASE after failed duplicate ATTACH.
            var duplicate: OpenFluxHttpVerificationClient? = null
            var refused = false
            try { duplicate = OpenFluxHttpVerificationClient(target, id) { true } }
            catch (_: java.io.IOException) { refused = true }
            finally { duplicate?.close() }
            check(refused)
            call(OpenFluxHttpIpc.POLL, read = ::snapshot)
        }
        proved(3) {
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                for (fd in listOf(false, true)) {
                    val input = Parcel.obtain(); val output = Parcel.obtain()
                    try {
                        input.writeInterfaceToken(OpenFluxHttpIpc.DESCRIPTOR); input.writeString(id); input.writeStrongBinder(peer)
                        if (fd) input.writeFileDescriptor(pipe[0].fileDescriptor) else input.writeByteArray(ByteArray(40000))
                        check(!target.transact(OpenFluxHttpIpc.POLL, input, output, 0) && output.dataSize() == 0)
                    } finally { input.recycle(); output.recycle() }
                }
            } finally { pipe.forEach { it.close() } }
            call(OpenFluxHttpIpc.POLL, read = ::snapshot)
        }
        proved(4) {
            val input = Parcel.obtain(); val output = Parcel.obtain()
            try {
                check(target.transact(IBinder.INTERFACE_TRANSACTION, input, output, 0))
                check(output.readString() == OpenFluxHttpIpc.DESCRIPTOR && output.dataAvail() == 0)
            } finally { input.recycle(); output.recycle() }
        }
        proved(5) { call(OpenFluxHttpIpc.RELEASE) { check(OpenFluxParcel.readInt(it) == 0) } }
        proved(6) { reject(OpenFluxHttpIpc.POLL); reject(OpenFluxHttpIpc.ATTACH); reject(OpenFluxHttpIpc.RELEASE) }
        return mask
    }
    /** Fixed binary transfer/Send suite using the production client. Caller owns
     * synthetic native responses; no provider, browser, general command API or kill. */
    private fun httpTransferSuite(target: IBinder, id: String): Int {
        var mask = 0
        val address = "http://127.0.0.1:4321/check"
        val client = OpenFluxHttpVerificationClient(target, id) { true }
        fun proved(bit: Int, action: () -> Unit) { action(); mask = mask or (1 shl bit) }
        fun response(request: String, expectedSize: Int, method: String): OpenFluxHttpIpcResponse.Response {
            repeat(66) {
                val value = client.pull(request)
                value.response?.let { result ->
                    try {
                        check(!value.waiting && result.request == request && result.status == 200 && result.body.size == expectedSize)
                        if (method == "GET") {
                            check(result.headers.size == 17 && result.headers.count { it.first.equals("X-" + "N".repeat(200), true) && it.second == "v".repeat(200) } == 16)
                            check(result.body.indices.all { result.body[it] == (it * 37).toByte() })
                        }
                        return result
                    } catch (error: Throwable) { result.close(); throw error }
                }
                check(value.waiting && value.progress)
            }
            error("bounded response unavailable")
        }
        try {
            proved(0) {
                check(client.snapshot()?.code == 0 && client.foreground(true) && client.openAfterUserAction())
                check(!client.openAfterUserAction() && client.poll()?.address == address)
            }
            proved(1) {
                val request = requireNotNull(client.enqueue("GET", address, emptyList(), byteArrayOf()))
                check(!client.submitAfterUserAction("{\"answer\":\"synthetic\"}"))
                val result = response(request, 524288, "GET")
                result.close(); check(result.body.all { it == 0.toByte() })
            }
            proved(2) {
                val body = ByteArray(65536) { (it * 13).toByte() }
                try {
                    val request = requireNotNull(client.enqueue("POST", address, emptyList(), body))
                    check(body.indices.all { body[it] == (it * 13).toByte() })
                    response(request, 0, "POST").close()
                } finally { body.fill(0) }
            }
            proved(3) { response(requireNotNull(client.enqueue("HEAD", address, emptyList(), byteArrayOf())), 0, "HEAD").close() }
            proved(4) {
                check(client.submitAfterUserAction("{\"answer\":\"synthetic\"}") && client.replyStatus() == 2)
                check(!client.submitAfterUserAction("{\"answer\":\"synthetic\"}"))
                check(client.enqueue("GET", address, emptyList(), byteArrayOf()) == null)
            }
            proved(5) { client.close(); check(!client.current()) }
            proved(6) { check(client.poll() == null && client.replyStatus() == -1); client.close() }
        } finally { client.close() }
        return mask
    }
    /** Fixed17-case negative suite selector, not arbitrary opcodes/payloads. A
     * fresh synthetic production endpoint is supplied for EACH terminal case. */
    private fun httpMalformedSuite(target: IBinder, id: String, scenario: Int): Int {
        val owner = Binder()
        fun call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> Unit) {
            val input = Parcel.obtain(); val output = Parcel.obtain()
            try {
                input.writeInterfaceToken(OpenFluxHttpIpc.DESCRIPTOR); input.writeString(id); input.writeStrongBinder(owner); write(input)
                check(target.transact(code, input, output, 0) && !output.hasFileDescriptors() && output.dataSize() <= 32768)
                read(output); check(output.dataAvail() == 0)
            } finally { input.recycle(); output.recycle() }
        }
        fun reject(code: Int, write: (Parcel) -> Unit = {}) = call(code, write) { check(OpenFluxParcel.readInt(it) == 1) }
        fun success(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> Unit = {}) =
            call(code, write) { check(OpenFluxParcel.readInt(it) == 0); read(it) }
        success(OpenFluxHttpIpc.ATTACH, read = {
            check(OpenFluxParcel.readInt(it) == 0 && OpenFluxParcel.readLong(it) in 1..150000 && it.readString() == "")
        })
        fun retired(): Int {
            reject(OpenFluxHttpIpc.POLL); reject(OpenFluxHttpIpc.RELEASE); return 1
        }
        if (scenario == 15) { reject(OpenFluxHttpIpc.OPEN); return retired() }
        success(OpenFluxHttpIpc.FOREGROUND, { it.writeInt(1) })
        if (scenario == 16) { reject(OpenFluxHttpIpc.OPEN); return retired() }
        success(OpenFluxHttpIpc.OPEN)
        success(OpenFluxHttpIpc.POLL, read = {
            check(OpenFluxParcel.readInt(it) == 2 && OpenFluxParcel.readLong(it) in 1..150000 && it.readString() == "http://127.0.0.1:4321/check")
        })
        var upload = ""
        if (scenario in 7..12) success(OpenFluxHttpIpc.BEGIN, {
            it.writeString("POST"); it.writeString("http://127.0.0.1:4321/check"); it.writeString("[]"); it.writeInt(8192)
        }, { upload = requireNotNull(it.readString()); check(upload.matches(Regex("[0-9a-f]{32}"))) })
        when (scenario) {
            0 -> reject(OpenFluxHttpIpc.POLL) { it.writeInt(123) }
            1 -> reject(OpenFluxHttpIpc.FOREGROUND)
            2 -> reject(OpenFluxHttpIpc.FOREGROUND) { it.writeInt(2) }
            in 3..6 -> reject(OpenFluxHttpIpc.BEGIN) {
                it.writeString("POST")
                it.writeString(if (scenario == 5) "https://example.invalid/" else "http://127.0.0.1:4321/check")
                it.writeString(if (scenario == 6) "[[\"Connection\",\"close\"]]" else "[]")
                if (scenario != 3) it.writeInt(if (scenario == 4) 65537 else 8192)
            }
            in 7..10 -> reject(OpenFluxHttpIpc.PUSH) {
                it.writeString(upload); it.writeInt(if (scenario == 10) 1 else 0)
                when (scenario) {
                    7 -> it.writeInt(-1)
                    8 -> it.writeInt(8193)
                    9 -> { it.writeInt(8); it.writeInt(123) }
                    10 -> { val body = ByteArray(8192) { 7 }; try { it.writeByteArray(body) } finally { body.fill(0) } }
                }
            }
            11, 12 -> reject(OpenFluxHttpIpc.COMMIT) { it.writeString(upload); if (scenario == 12) it.writeInt(123) }
            13 -> reject(OpenFluxHttpIpc.PULL) { it.writeString("a".repeat(32)); it.writeInt(0) }
            14 -> reject(OpenFluxHttpIpc.REPLY) { it.writeString("{\"answer\":true}"); it.writeInt(123) }
            else -> error("invalid fixed scenario")
        }
        return retired()
    }
    /** SAME Binder worker constructs/uses/closes client. A fixed rendezvous lets
     * the fixture unbind its own exact endpoint between calls, not swap clients,
     * kill a process, execute arbitrary commands or pretend kernel death. */
    private fun httpUnbindSuite(target: IBinder, id: String, rendezvous: IBinder): Int {
        var mask = 0
        val client = OpenFluxHttpVerificationClient(target, id) { true }
        try {
            check(client.current() && client.snapshot()?.code == 0); mask = mask or 1
            val input = Parcel.obtain(); val output = Parcel.obtain()
            try {
                input.writeInterfaceToken("org.mcrf.client.OpenFluxUnbindRendezvous.debug.v1")
                check(rendezvous.transact(1, input, output, 0) && !output.hasFileDescriptors() && output.dataSize() == 4)
                check(OpenFluxParcel.readInt(output) == 0 && output.dataAvail() == 0); mask = mask or 2
            } finally { input.recycle(); output.recycle() }
            check(client.poll() == null && client.replyStatus() == -1 && !client.current()); mask = mask or 4
            client.close(); client.close(); check(!client.current()); mask = mask or 8
        } finally { client.close() }
        return mask
    }
    companion object { const val DESCRIPTOR = "org.mcrf.client.OpenFluxReviewPeer.debug.v1" }
}

/** Kernel-assigned isolated UID, not a spoofed Binder identity or permission bypass. */
class OpenFluxReviewForeignPeerService : OpenFluxReviewPeerService() { override val foreign = true }
