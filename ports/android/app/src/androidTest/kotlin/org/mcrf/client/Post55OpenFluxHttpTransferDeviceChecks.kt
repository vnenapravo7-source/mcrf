package org.mcrf.client

import android.app.Application
import android.app.Instrumentation
import android.content.*
import android.net.Uri
import android.os.*
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.json.JSONArray
import org.json.JSONObject

/** NOTRUN. Actual Service/client and sameUID crossPID Binder. Synthetic captured
 * native sink only; not child stdout/native handlers/provider/renderer/network. */
object Post55OpenFluxHttpTransferDeviceChecks {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base = instrumentation.targetContext
        check(BuildConfig.DEBUG && base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(Application.getProcessName() == base.applicationInfo.processName && !OpenFluxVerificationWebProfile.ready)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        val registry = OpenFluxVerificationRequests.registry
        val child = UUID.randomUUID().toString().replace("-", "")
        val ticket = UUID.randomUUID().toString().replace("-", "")
        val permit = UUID.randomUUID().toString().replace("-", "")
        val address = "http://127.0.0.1:4321/check"
        val retained = AtomicReference<OpenFluxVerificationRegistry.Request>()
        val commands = CopyOnWriteArrayList<String>()
        val requests = AtomicInteger(); val replies = AtomicInteger(); val cancels = AtomicInteger()
        fun feed(prefix: String, data: JSONObject): OpenFluxHttpSession.Received {
            val raw = (prefix + data.toString()).toByteArray(Charsets.UTF_8)
            return try { retained.get().receiveHttp(child, raw) } finally { raw.fill(0) }
        }
        val request = requireNotNull(registry.registerHttp(child, OpenFluxHttpWire.Pending(ticket), { true }, { bytes ->
            val fields = bytes.toString(Charsets.US_ASCII).trimEnd('\n').split('|', limit = 5)
            check(fields[1] == ticket); commands.add(fields[0])
            when (fields[0]) {
                "OPENFLUX_HTTP_OPEN" -> {
                    check(fields.size == 3)
                    check(feed(OpenFluxHttpWire.OPENED, JSONObject().put("id", ticket).put("request", fields[2])
                        .put("permit", permit).put("address", address)) == OpenFluxHttpSession.Received.CONSUMED)
                }
                "OPENFLUX_HTTP_REQUEST" -> {
                    check(fields.size == 5 && fields[2] == permit)
                    val decoded = Base64.getDecoder().decode(fields[4])
                    try {
                        val value = JSONArray(decoded.toString(Charsets.UTF_8)); check(value.length() == 4 && value.getString(1) == address && value.getJSONArray(2).length() == 0)
                        val body = Base64.getDecoder().decode(value.getString(3))
                        val index = requests.incrementAndGet()
                        try {
                            val method = value.getString(0)
                            check(method == listOf("GET", "POST", "HEAD")[index - 1])
                            if (method == "POST") check(body.size == 65536 && body.indices.all { body[it] == (it * 13).toByte() })
                            else check(body.isEmpty())
                            val result = if (method == "GET") ByteArray(524288) { (it * 37).toByte() } else byteArrayOf()
                            try {
                                val headers = JSONArray()
                                if (method == "GET") {
                                    headers.put(JSONArray().put("Content-Type").put("application/octet-stream"))
                                    repeat(16) { headers.put(JSONArray().put("X-" + "N".repeat(200)).put("v".repeat(200))) }
                                }
                                check(feed(OpenFluxHttpWire.META, JSONObject().put("id", ticket).put("request", fields[3])
                                    .put("status", 200).put("headers", headers).put("bytes", result.size).put("sha256", hash(result))) == OpenFluxHttpSession.Received.CONSUMED)
                                var offset = 0
                                while (offset < result.size) {
                                    val chunk = result.copyOfRange(offset, minOf(offset + 8192, result.size))
                                    try { check(feed(OpenFluxHttpWire.CHUNK, JSONObject().put("id", ticket).put("request", fields[3])
                                        .put("offset", offset).put("data", Base64.getEncoder().encodeToString(chunk))) == OpenFluxHttpSession.Received.CONSUMED) }
                                    finally { chunk.fill(0) }
                                    offset += 8192
                                }
                                check(feed(OpenFluxHttpWire.END, JSONObject().put("id", ticket).put("request", fields[3])) == OpenFluxHttpSession.Received.COMPLETE)
                            } finally { result.fill(0) }
                        } finally { body.fill(0) }
                    } finally { decoded.fill(0) }
                }
                "OPENFLUX_HTTP_REPLY" -> {
                    check(fields.size == 5 && fields[2] == permit)
                    val body = Base64.getDecoder().decode(fields[4])
                    try { check(body.toString(Charsets.UTF_8) == "{\"answer\":\"synthetic\"}") } finally { body.fill(0) }
                    check(replies.incrementAndGet() == 1)
                    check(feed(OpenFluxHttpWire.RESULT, JSONObject().put("id", ticket).put("request", fields[3])
                        .put("code", "accepted")) == OpenFluxHttpSession.Received.COMPLETE)
                }
                "OPENFLUX_CAPTCHA_CANCEL" -> { check(fields.size == 2); cancels.incrementAndGet() }
                else -> error("unexpected fixture command")
            }
            true
        }))
        retained.set(request)
        val bindings = mutableListOf<ServiceConnection>()
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        fun bind(className: String): IBinder {
            val latch = CountDownLatch(1); val result = AtomicReference<IBinder?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { result.set(service); latch.countDown() }
                override fun onServiceDisconnected(name: ComponentName) { result.set(null); latch.countDown() }
                override fun onNullBinding(name: ComponentName) { latch.countDown() }
                override fun onBindingDied(name: ComponentName) { result.set(null); latch.countDown() }
            }
            instrumentation.runOnMainSync {
                val intent = Intent().setComponent(ComponentName(base.packageName, className))
                    .setData(Uri.parse("mcrf-http-transfer://request/${request.id}/${UUID.randomUUID()}"))
                    .putExtra("request", request.id)
                check(base.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Fixture binding refused; do not bypass" }
                bindings.add(connection)
            }
            check(latch.await(8, TimeUnit.SECONDS)) { "Fixture binding timed out; do not bypass" }
            return requireNotNull(result.get())
        }
        var primary: Throwable? = null
        try {
            val peerClass = "org.mcrf.client.OpenFluxReviewPeerService"
            val info = base.packageManager.getServiceInfo(ComponentName(base.packageName, peerClass), 0)
            verify(!info.exported && info.processName != base.applicationInfo.processName,
                "HTTP transfer fixture peer is nonexported in a separate process")
            val target = bind(OpenFluxHttpVerificationService::class.java.name); val peer = bind(peerClass)
            val input = Parcel.obtain(); val output = Parcel.obtain()
            val report: List<Int>
            try {
                input.writeInterfaceToken("org.mcrf.client.OpenFluxReviewPeer.debug.v1"); input.writeStrongBinder(target); input.writeString(request.id)
                check(peer.transact(3, input, output, 0))
                check(!output.hasFileDescriptors() && output.dataSize() <= 64 && OpenFluxParcel.readInt(output) == 0)
                report = listOf(OpenFluxParcel.readInt(output), OpenFluxParcel.readInt(output), OpenFluxParcel.readInt(output))
                check(output.dataAvail() == 0)
            } finally { input.recycle(); output.recycle() }
            verify(report[0] != Process.myPid() && report[1] == Process.myUid(), "HTTP transfer client runs actual sameUID crossPID calls")
            val cases = listOf("HTTP production Service/client attach and explicit OPEN accept once",
                "HTTP actual metadata Parcel and64pull binary512KiB transfer preserve headers/hash and consumer close wipes body",
                "HTTP actual64KiB upload crosses8PUSH operations without mutating caller bytes; empty response completes",
                "HTTP HEAD empty response completes exact terminal pull",
                "HTTP explicit Send accepts exact synthetic native ACK once and blocks further requests",
                "HTTP exact client close retires ownership",
                "HTTP late local poll/status/duplicate close cannot repeat terminal operations")
            cases.forEachIndexed { bit, name -> verify(report[2] and (1 shl bit) != 0, name) }
            verify(requests.get() == 3 && replies.get() == 1 && cancels.get() == 1 && commands.toList() == listOf(
                "OPENFLUX_HTTP_OPEN", "OPENFLUX_HTTP_REQUEST", "OPENFLUX_HTTP_REQUEST", "OPENFLUX_HTTP_REQUEST", "OPENFLUX_HTTP_REPLY", "OPENFLUX_CAPTCHA_CANCEL"),
                "HTTP captured synthetic native sink receives exact ordered scoped commands, no retry or extra traffic")
            verify(registry.get(request.id) == null && !registry.browserOwnedBy(request.id) && !OpenFluxVerificationWebProfile.ready,
                "HTTP transfer suite retires only synthetic request without creating WebView or touching user connections")
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            var cleanup: Throwable? = null
            try { instrumentation.runOnMainSync { bindings.asReversed().forEach { connection ->
                try { base.unbindService(connection) } catch (error: Throwable) {
                    if (cleanup == null) cleanup = error else cleanup!!.addSuppressed(error)
                }
            } } } catch (error: Throwable) { cleanup = error }
            finally { registry.closeChild(child); OpenFluxVerificationUi.cancel(base, request.id) }
            cleanup?.let { if (primary != null) primary!!.addSuppressed(it) else throw it }
        }
    }
}
