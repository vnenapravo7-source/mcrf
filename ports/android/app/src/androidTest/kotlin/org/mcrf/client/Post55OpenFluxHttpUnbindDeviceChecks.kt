package org.mcrf.client

import android.app.Application
import android.app.Instrumentation
import android.content.*
import android.net.Uri
import android.os.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** NOTRUN. Actual framework unbind between real crossPID client calls. No
 * sleeping deadline, fake Binder death, native child, Activity or process kill. */
object Post55OpenFluxHttpUnbindDeviceChecks {
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
        val cancelled = CountDownLatch(1); val cancels = AtomicInteger()
        val request = requireNotNull(registry.registerHttp(child, OpenFluxHttpWire.Pending(ticket), { true }, { bytes ->
            check(bytes.toString(Charsets.US_ASCII) == "OPENFLUX_CAPTCHA_CANCEL|$ticket\n")
            cancels.incrementAndGet(); cancelled.countDown(); true
        }))
        val bindings = mutableListOf<ServiceConnection>()
        data class Bound(val binder: IBinder, val connection: ServiceConnection)
        fun bind(className: String): Bound {
            val latch = CountDownLatch(1); val value = AtomicReference<IBinder?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { value.set(service); latch.countDown() }
                override fun onServiceDisconnected(name: ComponentName) { value.set(null); latch.countDown() }
                override fun onNullBinding(name: ComponentName) { latch.countDown() }
                override fun onBindingDied(name: ComponentName) { value.set(null); latch.countDown() }
            }
            instrumentation.runOnMainSync {
                val intent = Intent().setComponent(ComponentName(base.packageName, className))
                    .setData(Uri.parse("mcrf-http-unbind://request/${request.id}/${UUID.randomUUID()}"))
                    .putExtra("request", request.id)
                check(base.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Fixture binding refused; do not bypass" }
                bindings.add(connection)
            }
            check(latch.await(8, TimeUnit.SECONDS)) { "Fixture binding timed out; do not bypass" }
            return Bound(requireNotNull(value.get()), connection)
        }
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        var primary: Throwable? = null
        try {
            val peerClass = "org.mcrf.client.OpenFluxReviewPeerService"
            val info = base.packageManager.getServiceInfo(ComponentName(base.packageName, peerClass), 0)
            verify(!info.exported && info.processName != base.applicationInfo.processName,
                "HTTP unbind fixture uses private actual separate-process peer")
            val target = bind(OpenFluxHttpVerificationService::class.java.name); val peer = bind(peerClass)
            val observedClaim = AtomicBoolean(); val unbound = AtomicBoolean(); val rendezvousUsed = AtomicBoolean()
            val rendezvous = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != 1 || flags != 0 || reply == null || data.hasFileDescriptors() || data.dataSize() > 256 ||
                        Binder.getCallingUid() != Process.myUid() || Binder.getCallingPid() == Process.myPid()) return false
                    data.enforceInterface("org.mcrf.client.OpenFluxUnbindRendezvous.debug.v1")
                    check(data.dataAvail() == 0 && rendezvousUsed.compareAndSet(false, true))
                    observedClaim.set(registry.get(request.id) === request && registry.browserOwnedBy(request.id))
                    instrumentation.runOnMainSync {
                        check(bindings.contains(target.connection))
                        base.unbindService(target.connection); bindings.remove(target.connection); unbound.set(true)
                    }
                    check(cancelled.await(8, TimeUnit.SECONDS)) { "Actual service unbind cancellation not observed" }
                    reply.writeInt(0); return true
                }
            }
            val input = Parcel.obtain(); val output = Parcel.obtain()
            val report: List<Int>
            try {
                input.writeInterfaceToken("org.mcrf.client.OpenFluxReviewPeer.debug.v1")
                input.writeStrongBinder(target.binder); input.writeString(request.id); input.writeStrongBinder(rendezvous)
                check(peer.binder.transact(5, input, output, 0) && !output.hasFileDescriptors() && output.dataSize() <= 64)
                check(OpenFluxParcel.readInt(output) == 0)
                report = listOf(OpenFluxParcel.readInt(output), OpenFluxParcel.readInt(output), OpenFluxParcel.readInt(output))
                check(output.dataAvail() == 0)
            } finally { input.recycle(); output.recycle() }
            verify(report[0] != Process.myPid() && report[1] == Process.myUid(),
                "HTTP unbind client operates across actual sameUID separate PID")
            listOf("Client attaches unopened and is current before unbind",
                "Actual framework unbind rendezvous finishes only after scoped cancellation",
                "Client late POLL/status become terminal after endpoint unbind",
                "Client duplicate close after unbind is idempotent").forEachIndexed { bit, name ->
                verify(report[2] and (1 shl bit) != 0, "HTTP $name")
            }
            verify(observedClaim.get() && unbound.get() && rendezvousUsed.get() && cancels.get() == 1 &&
                registry.get(request.id) == null && !registry.browserOwnedBy(request.id) && !OpenFluxVerificationWebProfile.ready,
                "HTTP actual unbind retires exact owned synthetic request with one preOPEN cancel, no WebView or native traffic")
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
