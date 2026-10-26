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

/** Real framework-bound production server called from debug-only same-UID peer
 * and kernel-isolated foreign UID. Synthetic request/offers only; no WebView,
 * Activity, provider, native child or process/renderer killing. NOTRUN. */
object Post55OpenFluxServerParcelDeviceChecks {
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base = instrumentation.targetContext
        check(BuildConfig.DEBUG && base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(Application.getProcessName() == base.applicationInfo.processName && !OpenFluxVerificationWebProfile.ready)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        val ownClass = "org.mcrf.client.OpenFluxReviewPeerService"
        val foreignClass = "org.mcrf.client.OpenFluxReviewForeignPeerService"
        val registry = OpenFluxVerificationRequests.registry
        val child = UUID.randomUUID().toString().replace("-", "")
        val ticket = UUID.randomUUID().toString().replace("-", "")
        val commands = CopyOnWriteArrayList<String>()
        val request = requireNotNull(registry.registerInline(child, OpenFluxDocumentWire.Pending(ticket),
            { true }, { commands.add(it.toString(Charsets.US_ASCII)); true }))
        val bindings = mutableListOf<ServiceConnection>()
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        fun bind(className: String): IBinder {
            val latch = CountDownLatch(1); val value = AtomicReference<IBinder?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { value.set(service); latch.countDown() }
                override fun onServiceDisconnected(name: ComponentName) { value.set(null); latch.countDown() }
                override fun onNullBinding(name: ComponentName) { latch.countDown() }
                override fun onBindingDied(name: ComponentName) { value.set(null); latch.countDown() }
            }
            instrumentation.runOnMainSync {
                val intent = Intent().setComponent(ComponentName(base.packageName, className))
                    .setData(Uri.parse("mcrf-openflux-server-fixture://request/${request.id}/${UUID.randomUUID()}"))
                    .putExtra("request", request.id)
                check(base.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Debug fixture binding refused; do not bypass" }
                bindings.add(connection)
            }
            check(latch.await(8, TimeUnit.SECONDS)) { "Debug fixture binding timed out; do not bypass" }
            return requireNotNull(value.get()) { "Debug fixture returned no endpoint" }
        }
        data class Report(val pid: Int, val uid: Int, val mask: Int)
        fun runPeer(peer: IBinder, target: IBinder): Report {
            val input = Parcel.obtain(); val output = Parcel.obtain()
            try {
                input.writeInterfaceToken("org.mcrf.client.OpenFluxReviewPeer.debug.v1")
                input.writeStrongBinder(target); input.writeString(request.id)
                check(peer.transact(1, input, output, 0))
                check(!output.hasFileDescriptors() && output.dataSize() <= 64 && OpenFluxParcel.readInt(output) == 0) { "Remote server fixture failed" }
                return Report(OpenFluxParcel.readInt(output), OpenFluxParcel.readInt(output), OpenFluxParcel.readInt(output))
                    .also { check(output.dataAvail() == 0) }
            } finally { input.recycle(); output.recycle() }
        }
        var primaryFailure: Throwable? = null
        try {
            val own = base.packageManager.getServiceInfo(ComponentName(base.packageName, ownClass), 0)
            val foreign = base.packageManager.getServiceInfo(ComponentName(base.packageName, foreignClass), 0)
            verify(!own.exported && !foreign.exported && own.processName != base.applicationInfo.processName &&
                foreign.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS != 0,
                "Debug-only server peers are nonexported and foreign peer uses kernel isolated UID")
            val target = bind(OpenFluxVerificationService::class.java.name)
            val foreignReport = runPeer(bind(foreignClass), target)
            verify(foreignReport.pid != Process.myPid() && foreignReport.uid != Process.myUid() && foreignReport.mask == 1 &&
                registry.get(request.id) === request && !registry.browserOwnedBy(request.id) && commands.isEmpty(),
                "Actual foreign-UID call is denied before payload decode without claiming or modifying synthetic request")
            val report = runPeer(bind(ownClass), target)
            verify(report.pid != Process.myPid() && report.uid == Process.myUid(),
                "Production server suite calls originate from actual same-UID separate PID")
            val cases = listOf(
                "Server rejects wrong descriptor and request ID before ATTACH",
                "Server rejects missing peer and trailing ATTACH body without acquiring browser",
                "Server accepts exact inline ATTACH with bounded lifetime snapshot",
                "Duplicate ATTACH rejects without retiring the earlier owner; subsequent POLL works",
                "Malformed POLL/null or trailing SEND reject without retiring current owner",
                "Missing and invalid RELEASE primitive reject without releasing current owner",
                "Missing PULL offset rejects; negative offset is unavailable without retiring request",
                "Server rejects actual cross-process FD-bearing and oversized requests before decoding",
                "Exact OPEN admits once; repeat refuses and PULL waits without sending HTML",
                "Exact RELEASE succeeds and a late POLL cannot use released endpoint",
                "Late ATTACH and duplicate RELEASE cannot reacquire the released endpoint or repeat cancellation")
            cases.forEachIndexed { bit, name -> verify(report.mask and (1 shl bit) != 0, name) }
            verify(commands.toList() == listOf("OPENFLUX_DOCUMENT_READ|$ticket\n", "OPENFLUX_CAPTCHA_CANCEL|$ticket\n"),
                "Synthetic native sink receives exactly one own READ and one scoped CANCEL, no structured reply")
            verify(registry.get(request.id) == null && !registry.browserOwnedBy(request.id) && !OpenFluxVerificationWebProfile.ready,
                "Server suite retires exact synthetic request without selecting WebView profile or changing user connections")
        } catch (error: Throwable) { primaryFailure = error; throw error }
        finally {
            var cleanupFailure: Throwable? = null
            try {
                instrumentation.runOnMainSync { bindings.asReversed().forEach { connection ->
                    try { base.unbindService(connection) } catch (error: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                    }
                } }
            } catch (error: Throwable) { cleanupFailure = error }
            finally { registry.closeChild(child); OpenFluxVerificationUi.cancel(base, request.id) }
            cleanupFailure?.let { if (primaryFailure != null) primaryFailure!!.addSuppressed(it) else throw it }
        }
    }
}
