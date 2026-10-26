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

/** NOTRUN. Real production HTTP endpoint, fixed debug peer, synthetic registry.
 * Ownership/predecode/release only: no OPEN, upload, response, WebView or native child. */
object Post55OpenFluxHttpServerDeviceChecks {
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
        val commands = CopyOnWriteArrayList<String>()
        val request = requireNotNull(registry.registerHttp(child, OpenFluxHttpWire.Pending(ticket), { true },
            { commands.add(it.toString(Charsets.US_ASCII)); true }))
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
                    .setData(Uri.parse("mcrf-http-fixture://request/${request.id}/${UUID.randomUUID()}"))
                    .putExtra("request", request.id)
                check(base.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Fixture binding refused; do not bypass" }
                bindings.add(connection)
            }
            check(latch.await(8, TimeUnit.SECONDS)) { "Fixture binding timed out; do not bypass" }
            return requireNotNull(result.get())
        }
        fun runPeer(peer: IBinder, target: IBinder): List<Int> {
            val input = Parcel.obtain(); val output = Parcel.obtain()
            try {
                input.writeInterfaceToken("org.mcrf.client.OpenFluxReviewPeer.debug.v1")
                input.writeStrongBinder(target); input.writeString(request.id)
                check(peer.transact(2, input, output, 0))
                check(!output.hasFileDescriptors() && output.dataSize() <= 64 && OpenFluxParcel.readInt(output) == 0)
                return listOf(OpenFluxParcel.readInt(output), OpenFluxParcel.readInt(output), OpenFluxParcel.readInt(output))
                    .also { check(output.dataAvail() == 0) }
            } finally { input.recycle(); output.recycle() }
        }
        var primary: Throwable? = null
        try {
            val ownClass = "org.mcrf.client.OpenFluxReviewPeerService"
            val foreignClass = "org.mcrf.client.OpenFluxReviewForeignPeerService"
            val own = base.packageManager.getServiceInfo(ComponentName(base.packageName, ownClass), 0)
            val foreign = base.packageManager.getServiceInfo(ComponentName(base.packageName, foreignClass), 0)
            verify(!own.exported && !foreign.exported && own.processName != base.applicationInfo.processName &&
                foreign.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS != 0,
                "HTTP fixture peers are private and foreign UID is kernel-isolated")
            val target = bind(OpenFluxHttpVerificationService::class.java.name)
            val localInput = Parcel.obtain(); val localOutput = Parcel.obtain()
            try {
                localInput.writeInt(123)
                verify(!target.transact(OpenFluxHttpIpc.ATTACH, localInput, localOutput, 0) && localOutput.dataSize() == 0 &&
                    registry.get(request.id) === request && !registry.browserOwnedBy(request.id),
                    "HTTP server rejects actual same-PID caller before malformed payload decode")
            } finally { localInput.recycle(); localOutput.recycle() }
            val denied = runPeer(bind(foreignClass), target)
            verify(denied[0] != Process.myPid() && denied[1] != Process.myUid() && denied[2] == 1 &&
                registry.get(request.id) === request && !registry.browserOwnedBy(request.id) && commands.isEmpty(),
                "HTTP server rejects actual foreign UID before decode without claiming synthetic request")
            val report = runPeer(bind(ownClass), target)
            verify(report[0] != Process.myPid() && report[1] == Process.myUid(),
                "HTTP ownership suite runs from actual same-UID separate PID")
            val cases = listOf(
                "HTTP wrong descriptor cannot attach or claim browser",
                "HTTP exact peer ATTACH returns bounded unopened snapshot",
                "HTTP wrong peer and failed same-PID duplicate client cleanup preserve earlier owner",
                "HTTP actual remote FD and oversized Parcels reject without retiring earlier owner",
                "HTTP interface query returns exact private HTTP descriptor",
                "HTTP exact peer RELEASE succeeds",
                "HTTP late POLL, ATTACH and RELEASE cannot reacquire endpoint")
            cases.forEachIndexed { bit, name -> verify(report[2] and (1 shl bit) != 0, name) }
            verify(commands.toList() == listOf("OPENFLUX_CAPTCHA_CANCEL|$ticket\n") && registry.get(request.id) == null &&
                !registry.browserOwnedBy(request.id) && !OpenFluxVerificationWebProfile.ready,
                "HTTP release emits exactly one scoped pre-OPEN cancel, retires only synthetic request and never creates WebView")
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            var cleanup: Throwable? = null
            try {
                instrumentation.runOnMainSync { bindings.asReversed().forEach { connection ->
                    try { base.unbindService(connection) } catch (error: Throwable) {
                        if (cleanup == null) cleanup = error else cleanup!!.addSuppressed(error)
                    }
                } }
            } catch (error: Throwable) { cleanup = error }
            finally { registry.closeChild(child); OpenFluxVerificationUi.cancel(base, request.id) }
            cleanup?.let { if (primary != null) primary!!.addSuppressed(it) else throw it }
        }
    }
}
