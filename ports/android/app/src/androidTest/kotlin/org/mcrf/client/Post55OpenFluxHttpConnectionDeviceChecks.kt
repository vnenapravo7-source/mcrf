package org.mcrf.client

import android.app.Instrumentation
import android.content.*
import android.os.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/** NOTRUN. Actual helper/client/Looper/worker with synthetic framework binding
 * and LOCAL Binder. No real Service, remote death, blocked Binder or latency proof. */
object Post55OpenFluxHttpConnectionDeviceChecks {
    private enum class Failure { NONE, FALSE_BIND, THROW_BIND, NULL_BINDING, BINDING_DIED, DISCONNECTED, TIMEOUT_CALLBACK, BAD_ATTACH }
    private class FakeContext(base: Context, val id: String, val failure: Failure = Failure.NONE) : ContextWrapper(base) {
        val binds = AtomicInteger(); val unbinds = AtomicInteger(); val calls = AtomicInteger()
        val releases = AtomicInteger(); val retired = CountDownLatch(1)
        var callback: ServiceConnection? = null
        val component = ComponentName(base.packageName, OpenFluxHttpVerificationService::class.java.name)
        val endpoint = object : Binder() {
            private var peer: IBinder? = null
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                check(Looper.myLooper() != Looper.getMainLooper() && reply != null && flags == 0)
                data.enforceInterface(OpenFluxHttpIpc.DESCRIPTOR); check(data.readString() == id)
                val token = requireNotNull(data.readStrongBinder())
                if (code == OpenFluxHttpIpc.ATTACH) { check(peer == null); peer = token } else check(token == peer)
                check(data.dataAvail() == 0); calls.incrementAndGet()
                reply.writeInt(0)
                when (code) {
                    OpenFluxHttpIpc.ATTACH -> { reply.writeInt(0); reply.writeLong(if (failure == Failure.BAD_ATTACH) 0L else 120000L); reply.writeString("") }
                    OpenFluxHttpIpc.RELEASE -> { releases.incrementAndGet(); retired.countDown() }
                    else -> error("No OPEN, foreground or traffic expected")
                }
                return true
            }
        }
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            check(intent.component == component && intent.getStringExtra("request") == id && flags == Context.BIND_AUTO_CREATE)
            check(intent.dataString!!.length <= 160)
            binds.incrementAndGet(); callback = connection
            if (failure == Failure.FALSE_BIND) return false
            if (failure == Failure.THROW_BIND) throw IllegalStateException("Synthetic binding refusal")
            Handler(Looper.getMainLooper()).post {
                when (failure) {
                    Failure.NULL_BINDING -> connection.onNullBinding(component)
                    Failure.BINDING_DIED -> connection.onBindingDied(component)
                    Failure.DISCONNECTED -> connection.onServiceDisconnected(component)
                    Failure.TIMEOUT_CALLBACK -> Unit // Fixture invokes exact timeout runnable below, not a real clock proof.
                    else -> connection.onServiceConnected(component, endpoint)
                }
            }
            return true
        }
        override fun unbindService(connection: ServiceConnection) {
            check(Looper.myLooper() == Looper.getMainLooper() && connection === callback); unbinds.incrementAndGet()
        }
    }
    @JvmStatic fun run(instrumentation: Instrumentation, temporary: File, passed: Consumer<String>) {
        val base = instrumentation.targetContext
        check(BuildConfig.DEBUG && base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(Looper.myLooper() != Looper.getMainLooper() && !RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        val fake = FakeContext(base, UUID.randomUUID().toString())
        val ready = CountDownLatch(1); val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        val failures = AtomicInteger(); val completed = AtomicInteger()
        lateinit var owner: OpenFluxHttpConnection
        var bound = false
        instrumentation.runOnMainSync {
            owner = OpenFluxHttpConnection(fake, fake.id, { ready.countDown() }, { failures.incrementAndGet() })
            bound = owner.bind()
        }
        try {
            check(ready.await(8, TimeUnit.SECONDS)) { "Synthetic HTTP attach timed out" }
            verify(bound && owner.current() && owner.snapshot()?.code == 0 && failures.get() == 0,
                "HTTP connection synthetic attach publishes unopened client without native OPEN")
            verify(fake.calls.get() == 1 && fake.releases.get() == 0,
                "HTTP connection constructor IPC ran on its worker rather than main Looper")
            var rebound = true
            instrumentation.runOnMainSync { rebound = owner.bind() }
            verify(!rebound && fake.binds.get() == 1, "HTTP connection never retries framework bind on same owner")
            val successor = CountDownLatch(1); val successorAccepted = AtomicInteger()
            instrumentation.runOnMainSync {
                check(owner.executeThen({}, {
                    if (owner.executeThen({}, { successor.countDown() })) successorAccepted.incrementAndGet()
                }))
            }
            check(successor.await(8, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { /* Main-loop barrier after successor submission. */ }
            verify(successorAccepted.get() == 1,
                "HTTP completion releases the caller slot before UI can submit the next worker step")
            var accepted = false
            instrumentation.runOnMainSync {
                accepted = owner.execute {
                    check(Looper.myLooper() != Looper.getMainLooper()); entered.countDown()
                    check(unblock.await(8, TimeUnit.SECONDS)) { "Synthetic task release timed out" }
                    completed.incrementAndGet()
                }
            }
            check(entered.await(8, TimeUnit.SECONDS))
            var queuedAgain = true
            instrumentation.runOnMainSync { queuedAgain = owner.execute { error("Must not be queued") } }
            verify(accepted && !queuedAgain, "HTTP connection bounds caller tasks to one queued or active action")
            var closeReturned = false; var afterClose = true
            instrumentation.runOnMainSync {
                owner.close(); owner.close(); afterClose = owner.execute { error("Retired owner") }; closeReturned = true
            }
            verify(closeReturned && !afterClose && !owner.current() && owner.snapshot() == null &&
                fake.unbinds.get() == 1 && fake.releases.get() == 0 && completed.get() == 0,
                "HTTP close returns with synthetic worker still occupied, signals admission stop and unbinds without UI RPC")
            unblock.countDown()
            check(fake.retired.await(8, TimeUnit.SECONDS))
            verify(completed.get() == 1 && fake.releases.get() == 1 && fake.calls.get() == 2,
                "HTTP cleanup waits behind admitted task on same worker and emits one terminal RELEASE")
            instrumentation.runOnMainSync {
                fake.callback!!.onServiceConnected(fake.component, fake.endpoint)
                fake.callback!!.onServiceDisconnected(fake.component)
                fake.callback!!.onBindingDied(fake.component)
                fake.callback!!.onNullBinding(fake.component)
                owner.close()
            }
            verify(fake.binds.get() == 1 && fake.unbinds.get() == 1 && fake.calls.get() == 2 && failures.get() == 0,
                "HTTP late callbacks and duplicate close cannot reconnect, reattach or repeat terminal failure")
        } finally {
            unblock.countDown()
            instrumentation.runOnMainSync { owner.close() }
        }
        for (failure in Failure.values().filter { it != Failure.NONE }) {
            val rejected = FakeContext(base, UUID.randomUUID().toString(), failure)
            val failed = CountDownLatch(1); val failedCalls = AtomicInteger(); val readyCalls = AtomicInteger()
            lateinit var connection: OpenFluxHttpConnection
            instrumentation.runOnMainSync {
                connection = OpenFluxHttpConnection(rejected, rejected.id, { readyCalls.incrementAndGet() },
                    { failedCalls.incrementAndGet(); failed.countDown() })
                connection.bind()
                if (failure == Failure.TIMEOUT_CALLBACK) {
                    val timer = OpenFluxHttpConnection::class.java.getDeclaredField("timeout")
                        .apply { isAccessible = true }.get(connection) as Runnable
                    timer.run() // Deterministic branch coverage ONLY, not elapsed10s or blocked-ATTACH latency.
                }
            }
            try {
                check(failed.await(8, TimeUnit.SECONDS)) { "Synthetic HTTP failure callback missing: $failure" }
                val worker = OpenFluxHttpConnection::class.java.getDeclaredField("worker")
                    .apply { isAccessible = true }.get(connection) as ScheduledThreadPoolExecutor
                check(worker.awaitTermination(8, TimeUnit.SECONDS)) { "Synthetic HTTP worker cleanup incomplete: $failure" }
                instrumentation.runOnMainSync {
                    rejected.callback!!.onServiceConnected(rejected.component, rejected.endpoint)
                    rejected.callback!!.onNullBinding(rejected.component)
                    rejected.callback!!.onBindingDied(rejected.component)
                    rejected.callback!!.onServiceDisconnected(rejected.component)
                    connection.close(); connection.close()
                    val malformed = failure == Failure.BAD_ATTACH
                    verify(!connection.current() && connection.snapshot() == null && !connection.bind() &&
                        !connection.execute { error("Retired synthetic connection") } &&
                        failedCalls.get() == 1 && readyCalls.get() == 0 && rejected.binds.get() == 1 &&
                        rejected.unbinds.get() == 1 && rejected.calls.get() == (if (malformed) 2 else 0) &&
                        rejected.releases.get() == (if (malformed) 1 else 0) && worker.isTerminated,
                        "HTTP synthetic $failure retires once, drains worker and rejects late callbacks/rebind/traffic")
                }
            } finally { instrumentation.runOnMainSync { connection.close() } }
        }
    }
}
