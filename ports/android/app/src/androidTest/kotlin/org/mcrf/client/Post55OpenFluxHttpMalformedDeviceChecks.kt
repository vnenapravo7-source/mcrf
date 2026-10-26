package org.mcrf.client

import android.app.Application
import android.app.Instrumentation
import android.content.*
import android.net.Uri
import android.os.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.json.JSONObject

/** NOTRUN. Production endpoint, real crossPID Parcel, fresh synthetic request
 * per terminal negative. No native child/provider/browser/process killing. */
object Post55OpenFluxHttpMalformedDeviceChecks {
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base = instrumentation.targetContext
        check(BuildConfig.DEBUG && base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(Application.getProcessName() == base.applicationInfo.processName && !OpenFluxVerificationWebProfile.ready)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        val registry = OpenFluxVerificationRequests.registry
        val children = mutableListOf<String>(); val ids = mutableListOf<String>(); val bindings = mutableListOf<ServiceConnection>()
        fun verify(ok: Boolean, name: String) { check(ok) { name }; passed.accept(name) }
        fun bind(className: String, id: String): IBinder {
            val latch = CountDownLatch(1); val value = AtomicReference<IBinder?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { value.set(service); latch.countDown() }
                override fun onServiceDisconnected(name: ComponentName) { value.set(null); latch.countDown() }
                override fun onNullBinding(name: ComponentName) { latch.countDown() }
                override fun onBindingDied(name: ComponentName) { value.set(null); latch.countDown() }
            }
            instrumentation.runOnMainSync {
                val intent = Intent().setComponent(ComponentName(base.packageName, className))
                    .setData(Uri.parse("mcrf-http-negative://request/$id/${UUID.randomUUID()}"))
                    .putExtra("request", id)
                check(base.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Fixture binding refused; do not bypass" }
                bindings.add(connection)
            }
            check(latch.await(8, TimeUnit.SECONDS)) { "Fixture binding timed out; do not bypass" }
            return requireNotNull(value.get())
        }
        val cases = listOf("owned trailing POLL", "missing foreground primitive", "invalid foreground primitive",
            "missing upload total", "upload over64KiB", "external upload target", "forbidden framing header",
            "negative embedded bytearray length", "embedded bytearray over8192", "truncated padded bytearray payload",
            "out-of-order upload offset", "incomplete upload COMMIT", "trailing upload COMMIT",
            "PULL before owned response metadata", "trailing structured reply", "OPEN without foreground",
            "uncertain OPEN writer admission")
        var primary: Throwable? = null
        try {
            val peerClass = "org.mcrf.client.OpenFluxReviewPeerService"
            val info = base.packageManager.getServiceInfo(ComponentName(base.packageName, peerClass), 0)
            verify(!info.exported && info.processName != base.applicationInfo.processName,
                "HTTP malformed fixture uses a private separate-process fixed-case peer")
            val peer = bind(peerClass, UUID.randomUUID().toString())
            cases.forEachIndexed { scenario, name ->
                val child = UUID.randomUUID().toString().replace("-", ""); children.add(child)
                val ticket = UUID.randomUUID().toString().replace("-", "")
                val permit = UUID.randomUUID().toString().replace("-", "")
                val commands = CopyOnWriteArrayList<String>()
                val retained = AtomicReference<OpenFluxVerificationRegistry.Request>()
                val request = requireNotNull(registry.registerHttp(child, OpenFluxHttpWire.Pending(ticket), { true }, { command ->
                    val parts = command.toString(Charsets.US_ASCII).trimEnd('\n').split('|')
                    check(parts[1] == ticket); commands.add(parts[0])
                    if (parts[0] == "OPENFLUX_HTTP_OPEN") {
                        check(parts.size == 3)
                        val frame = (OpenFluxHttpWire.OPENED + JSONObject().put("id", ticket).put("request", parts[2])
                            .put("permit", permit).put("address", "http://127.0.0.1:4321/check")).toByteArray(Charsets.UTF_8)
                        try { check(retained.get().receiveHttp(child, frame) == OpenFluxHttpSession.Received.CONSUMED) } finally { frame.fill(0) }
                    } else check(parts[0] == "OPENFLUX_CAPTCHA_CANCEL" && parts.size == 2)
                    scenario != 16 || parts[0] != "OPENFLUX_HTTP_OPEN" // Record then return uncertain false; NEVER retry.
                }))
                ids.add(request.id)
                retained.set(request)
                val target = bind(OpenFluxHttpVerificationService::class.java.name, request.id)
                val input = Parcel.obtain(); val output = Parcel.obtain()
                try {
                    input.writeInterfaceToken("org.mcrf.client.OpenFluxReviewPeer.debug.v1")
                    input.writeStrongBinder(target); input.writeString(request.id); input.writeInt(scenario)
                    check(peer.transact(4, input, output, 0))
                    check(!output.hasFileDescriptors() && output.dataSize() <= 64 && OpenFluxParcel.readInt(output) == 0)
                    val pid = OpenFluxParcel.readInt(output); val uid = OpenFluxParcel.readInt(output); val result = OpenFluxParcel.readInt(output)
                    check(output.dataAvail() == 0)
                    val expected = if (scenario == 15) listOf("OPENFLUX_CAPTCHA_CANCEL") else listOf("OPENFLUX_HTTP_OPEN", "OPENFLUX_CAPTCHA_CANCEL")
                    verify(pid != Process.myPid() && uid == Process.myUid() && result == 1 &&
                        commands.toList() == expected && registry.get(request.id) == null && !registry.browserOwnedBy(request.id),
                        "HTTP $name: fixed error, exact endpoint retirement and one scoped cancel, no replay or request/reply admission")
                } finally { input.recycle(); output.recycle() }
            }
            verify(ids.all { registry.get(it) == null && !registry.browserOwnedBy(it) } && !OpenFluxVerificationWebProfile.ready,
                "HTTP malformed batch leaves no synthetic child/browser owner and never creates WebView")
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            var cleanup: Throwable? = null
            try { instrumentation.runOnMainSync { bindings.asReversed().forEach { connection ->
                try { base.unbindService(connection) } catch (error: Throwable) {
                    if (cleanup == null) cleanup = error else cleanup!!.addSuppressed(error)
                }
            } } } catch (error: Throwable) { cleanup = error }
            finally { children.forEach { registry.closeChild(it) } }
            cleanup?.let { if (primary != null) primary!!.addSuppressed(it) else throw it }
        }
    }
}
