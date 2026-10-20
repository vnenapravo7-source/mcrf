package org.mcrf.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.engine.spi.v1.Platform
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Blocking client: connect/validate/start/controls/close must run off the UI thread.
 * A session may validate repeatedly before its single run. Reconfiguration/reload means close,
 * await death, then connect a new session (and supply a fresh generation/platform from main).
 * requestStop alone is nonblocking and safe on UI. It is never a termination acknowledgement.
 */
class ClientPrimaryWorker private constructor(
    private val context: Context,
    val choiceId: String,
    val generation: Long,
    private val hostPlatform: Platform?,
    private val serviceClass: Class<out ClientPrimaryWorkerService>,
    private val usage: EngineUpdateGate.Usage,
    private val onDeath: () -> Unit
) : AutoCloseable {
    /** A failed connect/start must never hide an owned worker whose death is still pending.
     * Callers must retain worker and retry close before releasing their carrier/TUN resources.
     */
    class CleanupPending(val worker: ClientPrimaryWorker) : IllegalStateException("PRIMARY_WORKER_CLEANUP_PENDING")
    private val stopping = AtomicBoolean()
    private val started = AtomicBoolean()
    private val released = AtomicBoolean()
    private val boundReady = CountDownLatch(1)
    private val dead = CountDownLatch(1)
    private val connected = CompletableFuture<IBinder>()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<Bundle>>()
    private val inputs = ConcurrentHashMap.newKeySet<File>()
    private val directories = ConcurrentHashMap.newKeySet<File>()
    private val owner = Binder()
    private val commandLock = Any()
    private val resourceLock = Any()
    private val slot = serviceClass.name
    private val processName = context.packageName + ClientPrimaryWorkerWire.processSuffix(serviceClass)
    @Volatile private var remote: IBinder? = null
    @Volatile private var pid = -1
    @Volatile private var processBirth: String? = null
    @Volatile private var bound = false
    @Volatile private var handshake = false
    val remotePid: Int get() = pid
    /** Lifecycle only. STARTING (Steer) and RUNNING (sing-box) never assert reachability. */
    @Volatile var state: String = "BOUND"
        private set
    @Volatile var steerProxyPort: Int = 0
        private set
    private val deathRecipient = IBinder.DeathRecipient { observedDeath() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!connected.complete(service)) return
        }
        override fun onServiceDisconnected(name: ComponentName) {
            // Framework disconnect alone is not proof of native death; DeathRecipient is authoritative.
            failPending("PRIMARY_WORKER_DISCONNECTED")
        }
        override fun onBindingDied(name: ComponentName) {
            connected.completeExceptionally(IllegalStateException("PRIMARY_WORKER_BINDING_DIED"))
            failPending("PRIMARY_WORKER_BINDING_DIED")
            if (remote?.isBinderAlive == false) observedDeath()
        }
        override fun onNullBinding(name: ComponentName) {
            connected.completeExceptionally(IllegalStateException("PRIMARY_WORKER_NULL_BINDING"))
        }
    }

    fun tunPlan(profile: String, settings: String, mode: Engine.Mode): String {
        offUi()
        synchronized(commandLock) {
            check(!started.get()) { "PRIMARY_WORKER_ALREADY_RUN" }
            val output = synchronized(resourceLock) {
                check(!stopping.get()) { "PRIMARY_WORKER_STOPPING" }
                ClientPrimaryWorkerWire.createOutput(context).also { inputs.add(it) }
            }
            try {
                withInput(profile, settings) { file ->
                    call("PLAN", 15000) {
                        putString("file", file.path); putString("output", output.path); putString("mode", mode.name)
                    }
                }
                return ClientPrimaryWorkerWire.readOutput(context, output)
            } finally { ClientPrimaryWorkerWire.removeInput(context, output); inputs.remove(output) }
        }
    }

    fun validate(profile: String, settings: String, mode: Engine.Mode): Engine.Validation {
        offUi()
        synchronized(commandLock) {
            check(!started.get()) { "PRIMARY_WORKER_ALREADY_RUN" }
            val reply = withInput(profile, settings) { file ->
                call("VALIDATE", 15000) { putString("file", file.path); putString("mode", mode.name) }
            }
            if (!reply.getBoolean("supported")) return Engine.Validation(false, "PRIMARY_CORE_CONFIG_REJECTED", "config")
            check(reply.getString("mode") == mode.name) { "PRIMARY_WORKER_VALIDATION_MODE" }
            val acquisition = ClientPrimaryWorkerWire.acquisition(ClientPrimaryCorePolicy.Core.parse(choiceId), mode)
            check(reply.getString("acquisition") == acquisition.name) { "PRIMARY_WORKER_VALIDATION_MODE" }
            // Safe summary DTO only. START repeats native validation and uses the worker's original DTO.
            return when (acquisition) {
                Engine.TunAcquisition.NONE -> Engine.Validation.proxyValidated(profile, settings)
                Engine.TunAcquisition.DEFERRED -> Engine.Validation.deferredVpnValidated(profile, settings,
                    requireNotNull(reply.getString("capabilitySha256")))
                Engine.TunAcquisition.PROVIDED -> Engine.Validation(true, "", "")
            }
        }
    }

    @JvmOverloads
    fun start(profile: String, settings: String, mode: Engine.Mode,
              providedTun: ParcelFileDescriptor? = null, dial: String = "",
              dnsBootstrap: Array<String> = emptyArray(), vpnNetworkHandle: Long = 0) {
        offUi()
        synchronized(commandLock) {
            check(hostPlatform != null) { "PRIMARY_WORKER_PLATFORM_REQUIRED" }
            check(!stopping.get()) { "PRIMARY_WORKER_STOPPING" }
            val acquisition = ClientPrimaryWorkerWire.acquisition(ClientPrimaryCorePolicy.Core.parse(choiceId), mode)
            require((acquisition == Engine.TunAcquisition.PROVIDED) == (providedTun != null)) { "PRIMARY_WORKER_TUN_MODE" }
            require(vpnNetworkHandle >= 0 && (acquisition == Engine.TunAcquisition.PROVIDED || vpnNetworkHandle == 0L)) { "PRIMARY_WORKER_TUN_MODE" }
            require(choiceId != "steer" || vpnNetworkHandle > 0L) { "PRIMARY_WORKER_VPN_NETWORK_REQUIRED" }
            require(dnsBootstrap.size <= 8) { "PRIMARY_WORKER_BOOTSTRAP" }
            check(started.compareAndSet(false, true)) { "PRIMARY_WORKER_ALREADY_RUN" }
            try {
                // Main remembers this path so even forced worker death cannot orphan native state files.
                val directory = synchronized(resourceLock) {
                    check(!stopping.get()) { "PRIMARY_WORKER_STOPPING" }
                    ClientPrimaryWorkerWire.makeRunDirectory(context).also { directories.add(it) }
                }
                val result = withInput(profile, settings, dial, dnsBootstrap, vpnNetworkHandle) { file ->
                    call("START", 25000) {
                        putString("file", file.path); putString("mode", mode.name)
                        putString("stateDirectory", directory.path)
                        // Binder duplicates this descriptor. Main's original remains main-owned.
                        if (providedTun != null) putParcelable("tun", providedTun)
                    }
                }
                val actualState = result.getString("status")
                check(ClientPrimaryWorkerWire.acknowledgesStart(ClientPrimaryCorePolicy.Core.parse(choiceId), actualState) &&
                    !stopping.get() && remote?.isBinderAlive == true) { "PRIMARY_WORKER_NOT_RUNNING" }
                if (choiceId == "steer") steerProxyPort = ClientSteerStartMetadata.workerPort(result.get("steerProxyPort"), JSONObject(settings))
                state = requireNotNull(actualState)
                if (stopping.get()) { steerProxyPort = 0; state = if (released.get()) "STOPPED" else "STOPPING" }
            } catch (error: Throwable) {
                requestStop()
                try { close() } catch (_: Exception) { throw CleanupPending(this) }
                throw error
            }
        }
    }

    /** Candidate packet path: existing owner, private process and death barrier, no primary switch. */
    fun startPacket(profile: String, providedTun: ParcelFileDescriptor, dial: String, settings:String="{}",ownedBlockFixture:Boolean=false,vpnNetworkHandle:Long=0) {
        offUi()
        require(!ownedBlockFixture||BuildConfig.DEBUG) {"PACKET_BLOCK_FIXTURE_DEBUG_ONLY"}
        val managed=JSONObject(settings).optInt("schema",1)==3
        require(if(managed)vpnNetworkHandle>0 else vpnNetworkHandle==0L){"PACKET_DNS_VPN_HANDLE"}
        synchronized(commandLock) {
            check(choiceId == "wg-awg" && hostPlatform != null && !stopping.get() && started.compareAndSet(false,true)) { "PACKET_WORKER_START_STATE" }
            try {
                val result = withInput(profile,settings,dial,vpnNetworkHandle=vpnNetworkHandle) { file ->
                    call("START_PACKET",25000) {putString("file",file.path);putParcelable("tun",providedTun);putBoolean("ownedBlockFixture",ownedBlockFixture)}
                }
                check(result.getString("status") == "PACKET_RUNNING" && !stopping.get() && remote?.isBinderAlive == true) { "PACKET_WORKER_NOT_RUNNING" }
                check(result.getString("routeSettingsSha256")==ClientPrimaryWorkerWire.sha256(settings.toByteArray(Charsets.UTF_8))&&
                    result.get("ownedBlockFixture")==ownedBlockFixture&&result.get("managedDns")==managed&&result.get("vpnNetworkHandle")==vpnNetworkHandle) {"PACKET_WORKER_ROUTE_ACK"}
                state = "PACKET_RUNNING"
            } catch(error: Throwable) {
                requestStop()
                try {close()} catch(_: Exception) {throw CleanupPending(this)}
                throw error
            }
        }
    }

    /** Actual native URL test through the verified installed sing-box entry. Milliseconds. */
    fun health(tag: String, url: String, timeout: Int): Long {
        offUi()
        ClientPrimaryWorkerWire.smallString(tag, 512); ClientPrimaryWorkerWire.smallString(url, 8192)
        require(timeout in 1..30000) { "PRIMARY_WORKER_TIMEOUT" }
        return call("HEALTH", timeout.toLong() + 3000) {
            putString("tag", tag); putString("url", url); putInt("timeout", timeout)
        }.getLong("latency")
    }
    fun resetNetwork() { offUi(); call("RESET", 8000) }
    fun selectOutbound(group: String, tag: String) {
        offUi(); ClientPrimaryWorkerWire.smallString(group, 512); ClientPrimaryWorkerWire.smallString(tag, 512)
        call("SELECT", 8000) { putString("group", group); putString("tag", tag) }
    }
    fun selectedOutbound(group: String): String {
        offUi(); ClientPrimaryWorkerWire.smallString(group, 512)
        return requireNotNull(call("SELECTED", 8000) { putString("group", group) }.getString("tag"))
    }
    fun stats(): Engine.Stats {
        offUi()
        val result = call("STATS", 5000)
        fun metric(key: String): Engine.Metric = if (result.containsKey(key)) Engine.Metric.of(result.getLong(key)) else Engine.Metric.unavailable()
        return Engine.Stats(metric("tx"), metric("rx"), metric("blocked"))
    }

    /** Safe from any thread; wake blocking calls immediately. close() still must be awaited. */
    fun requestStop() {
        if (!stopping.compareAndSet(false, true)) return
        steerProxyPort = 0; state = "STOPPING"
        failPending("PRIMARY_WORKER_STOPPING")
        val target = remote ?: return
        if (!handshake || !target.isBinderAlive) return
        try {
            send(target, "STOP", object : ResultReceiver(null) {}, Bundle())
        } catch (_: Exception) {
            if (!target.isBinderAlive) observedDeath()
        }
    }

    /** Returns only after actual Binder death, main onDeath cleanup, unbind and usage release.
     * On an unverifiable/unresponsive process, throws while retaining binding/lease until death.
     * Idempotent and concurrent-safe; does not hold commandLock while waiting for native shutdown.
     */
    override fun close() {
        offUi()
        requestStop()
        var interrupted = Thread.interrupted()
        try {
            fun waitForDeath(timeout: Long): Boolean {
                val deadline = System.nanoTime() + timeout * 1_000_000
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return dead.count == 0L
                    try { return dead.await(remaining, TimeUnit.NANOSECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            }
            if (waitForDeath(6000)) return
            val target = remote
            // isBinderAlive may turn false before its death callback gets scheduled.
            if (handshake && target != null && !target.isBinderAlive) observedDeath()
            if (dead.count == 0L) return
            if (handshake && target?.isBinderAlive == true && verifiedProcess()) Process.killProcess(pid)
            if (!waitForDeath(4000)) throw IllegalStateException("PRIMARY_WORKER_DEATH_TIMEOUT_LEASE_RETAINED")
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun verifiedProcess(): Boolean = try {
        pid > 0 && pid != Process.myPid() && Os.stat("/proc/$pid").st_uid == Process.myUid() &&
            File("/proc/$pid/cmdline").inputStream().use { input ->
                val bytes = ByteArray(1024); val size = input.read(bytes)
                size > 0 && String(bytes, 0, size, Charsets.UTF_8).substringBefore('\u0000') == processName
            } && processBirth != null && birth(pid) == processBirth
    } catch (_: Exception) { false }

    private fun call(command: String, timeout: Long, fields: Bundle.() -> Unit = {}): Bundle {
        check(!stopping.get()) { "PRIMARY_WORKER_STOPPING" }
        val target = requireNotNull(remote) { "PRIMARY_WORKER_NOT_CONNECTED" }
        val future = CompletableFuture<Bundle>()
        pending.add(future)
        try {
            if (stopping.get()) throw IllegalStateException("PRIMARY_WORKER_STOPPING")
            val receiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultData == null || resultData.getLong("generation") != generation) {
                        future.completeExceptionally(IllegalStateException("PRIMARY_WORKER_STALE_REPLY")); return
                    }
                    val error = resultData.getString("error")
                    if (error != null) future.completeExceptionally(IllegalStateException(safeError(error)))
                    else future.complete(resultData)
                }
            }
            send(target, command, receiver, Bundle().apply(fields))
            return try { future.get(timeout, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) {
                requestStop()
                throw IllegalStateException("PRIMARY_WORKER_COMMAND_TIMEOUT")
            } catch (error: ExecutionException) {
                throw IllegalStateException(safeError(error.cause?.message))
            } catch (_: InterruptedException) {
                requestStop(); Thread.currentThread().interrupt()
                throw IllegalStateException("PRIMARY_WORKER_INTERRUPTED")
            }
        } catch (error: android.os.RemoteException) {
            if (!target.isBinderAlive) observedDeath()
            throw IllegalStateException("PRIMARY_WORKER_IPC_DIED")
        } finally { pending.remove(future) }
    }

    private fun send(target: IBinder, command: String, receiver: ResultReceiver, fields: Bundle) {
        fields.putString("command", command); fields.putLong("generation", generation); fields.putParcelable("receiver", receiver)
        // Oneway Binder calls deliberately have no caller PID. The kernel-backed owner
        // capability from the synchronous handshake authenticates every queued command.
        fields.putBinder("owner", owner)
        val parcel = Parcel.obtain()
        try {
            parcel.writeInterfaceToken(ClientPrimaryWorkerWire.DESCRIPTOR); parcel.writeBundle(fields)
            check(target.transact(ClientPrimaryWorkerWire.TRANSACTION, parcel, null, IBinder.FLAG_ONEWAY)) { "PRIMARY_WORKER_IPC_REJECTED" }
        } finally { parcel.recycle() }
    }

    private inline fun <T> withInput(profile: String, settings: String, dial: String = "",
                                   dnsBootstrap: Array<String> = emptyArray(), vpnNetworkHandle: Long = 0,
                                   work: (File) -> T): T {
        require(profile.length.toLong() + settings.length <= ClientPrimaryWorkerWire.MAX_INPUT &&
            dial.length <= 1024 && dnsBootstrap.size <= 8 && dnsBootstrap.all { it.length <= 1024 }) { "PRIMARY_WORKER_INPUT_SIZE" }
        val file = synchronized(resourceLock) {
            check(!stopping.get()) { "PRIMARY_WORKER_STOPPING" }
            ClientPrimaryWorkerWire.writeInput(context, JSONObject().put("profile", profile).put("settings", settings)
                .put("dial", dial).put("dnsBootstrap", JSONArray(dnsBootstrap.toList())).put("vpnNetworkHandle", vpnNetworkHandle))
                .also { inputs.add(it) }
        }
        try { return work(file) }
        finally { ClientPrimaryWorkerWire.removeInput(context, file); inputs.remove(file) }
    }

    private fun failPending(code: String) { pending.forEach { it.completeExceptionally(IllegalStateException(code)) } }

    private fun observedDeath() {
        if (!released.compareAndSet(false, true)) return
        stopping.set(true)
        steerProxyPort = 0; state = "STOPPED"
        failPending("PRIMARY_WORKER_DIED")
        // Never run main cleanup inside the platform's Binder callback stack.
        Thread({
            try {
                synchronized(resourceLock) {
                    inputs.forEach { ClientPrimaryWorkerWire.removeInput(context, it) }; inputs.clear()
                    directories.forEach { ClientPrimaryWorkerWire.removeRunDirectory(context, it) }; directories.clear()
                }
                try { onDeath() } catch (_: Throwable) {} finally {
                    boundReady.await()
                    if (bound) { try { context.unbindService(connection) } catch (_: Exception) {}; bound = false }
                    try { remote?.unlinkToDeath(deathRecipient, 0) } catch (_: Exception) {}
                    usage.close()
                }
            } finally { slots.remove(slot, this); dead.countDown() }
        }, "mcrf-primary-death").start()
    }

    private fun bindAndInitialize(digest: String) {
        val info = context.packageManager.getServiceInfo(ComponentName(context, serviceClass), 0)
        check(!info.exported && info.enabled && info.applicationInfo.uid == Process.myUid() && info.processName == processName &&
            processName != android.app.Application.getProcessName()) { "PRIMARY_WORKER_MANIFEST_PROCESS" }
        try {
            // The live VPN worker is part of the foreground connection, not an
            // unrelated cached package. Probe workers retain ordinary binding.
            val priority=if(serviceClass==ClientPrimaryWorkerService::class.java)Context.BIND_IMPORTANT else 0
            bound = context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE or priority)
            check(bound) { "PRIMARY_WORKER_BIND_FAILED" }
        } finally { boundReady.countDown() }
        val target = try { connected.get(8000, TimeUnit.MILLISECONDS) }
            catch (_: Exception) { throw IllegalStateException("PRIMARY_WORKER_BIND_TIMEOUT") }
        remote = target
        val request = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(ClientPrimaryWorkerWire.DESCRIPTOR)
            request.writeBundle(Bundle().apply { putString("command", "BIND"); putBinder("owner", owner); putLong("generation", generation) })
            check(target.transact(ClientPrimaryWorkerWire.TRANSACTION, request, reply, 0)) { "PRIMARY_WORKER_HANDSHAKE" }
            reply.readException()
            val result = requireNotNull(reply.readBundle(javaClass.classLoader))
            pid = result.getInt("pid", -1)
            check(result.getInt("uid", -1) == Process.myUid() && result.getString("process") == processName &&
                result.getLong("generation") == generation && pid > 0 && pid != Process.myPid()) { "PRIMARY_WORKER_PID_IDENTITY" }
            // The authenticated worker can read /proc/self even when Android
            // hides sibling /proc entries. This proof never authorizes PID kills.
            val selfBirth = result.getString("birth")
            check(ClientPrimaryIdentityProof.validSelfProof(result.getString("identity"), selfBirth)) { "PRIMARY_WORKER_PID_IDENTITY" }
            processBirth = selfBirth
            handshake = true
            try { target.linkToDeath(deathRecipient, 0) }
            catch (_: android.os.RemoteException) { observedDeath(); throw IllegalStateException("PRIMARY_WORKER_DIED") }
        } finally { request.recycle(); reply.recycle() }
        call(if(choiceId == "wg-awg") "INIT_PACKET" else "INIT", 20000) {
            putString("choice", choiceId); putString("apkSha256", digest)
            hostPlatform?.let { putBinder("platform", ClientPrimaryPlatformBinder.stub(it, generation)) }
        }
        if (!stopping.get()) state = "READY"
    }

    private fun abandonBeforeHandshake() {
        // INIT cannot run before a verified handshake. No native code/worker lease exists yet.
        stopping.set(true)
        boundReady.countDown()
        if (released.compareAndSet(false, true)) {
            try { if (bound) context.unbindService(connection) } catch (_: Exception) {}
            finally { bound = false; usage.close(); slots.remove(slot, this); dead.countDown() }
        }
    }

    companion object {
        private val slots = ConcurrentHashMap<String, ClientPrimaryWorker>()
        private fun offUi() { check(Looper.myLooper() != Looper.getMainLooper()) { "PRIMARY_WORKER_OFF_UI_REQUIRED" } }
        private fun safeError(code: String?): String = if (code != null && code.matches(Regex("PRIMARY_[A-Z0-9_]{1,96}"))) code else "PRIMARY_WORKER_FAILED"
        private fun birth(pid: Int): String {
            val stat = File("/proc/$pid/stat").readText()
            return stat.substringAfterLast(") ").split(' ')[19]
        }

        /** platform=null permits preflight only. expectedApkSha256 may pin a durable verified receipt.
         * Otherwise pin the installed same-signer snapshot
         * inspected under this lease; the worker independently verifies those exact bytes again.
         * onDeath is invoked on a cleanup thread after real death, before unbind/lease release;
         * main should close its TUN there. Do not call blocking close from inside that callback.
         */
        @JvmStatic @JvmOverloads
        fun connect(context: Context, choiceId: String, generation: Long, platform: Platform? = null,
                    serviceClass: Class<out ClientPrimaryWorkerService> = ClientPrimaryWorkerService::class.java,
                    expectedApkSha256: String? = null, onDeath: () -> Unit = {}): ClientPrimaryWorker {
            ClientPrimaryCorePolicy.Core.parse(choiceId)
            return connectOwned(context,choiceId,generation,platform,serviceClass,expectedApkSha256,onDeath,false)
        }

        fun connectPacket(context: Context, generation: Long, platform: Platform, expectedApkSha256: String,
                          onDeath: () -> Unit = {}): ClientPrimaryWorker =
            connectOwned(context,"wg-awg",generation,platform,ClientPrimaryWorkerService::class.java,expectedApkSha256,onDeath,true)

        private fun connectOwned(context: Context, choiceId: String, generation: Long, platform: Platform?,
                    serviceClass: Class<out ClientPrimaryWorkerService>, expectedApkSha256: String?,
                    onDeath: () -> Unit, packet: Boolean): ClientPrimaryWorker {
            offUi()
            require(generation > 0) { "PRIMARY_WORKER_GENERATION" }
            val app = context.applicationContext
            val core = if(packet) null else ClientPrimaryCorePolicy.Core.parse(choiceId)
            ClientPrimaryWorkerWire.processSuffix(serviceClass)
            val lease = EngineUpdateGate.acquireUsage(app) ?: throw IllegalStateException("PRIMARY_CORE_UPDATE_BUSY")
            var session: ClientPrimaryWorker? = null
            try {
                val info = if(packet) app.packageManager.getApplicationInfo(ClientPacketBackendLoader.PACKAGE,0)
                    else EngineUpdatePrimary.installation(app, requireNotNull(core).kind)
                val digest = ClientPrimaryWorkerWire.hashFile(File(info.sourceDir).canonicalFile)
                require(expectedApkSha256 == null || expectedApkSha256.matches(Regex("[0-9a-fA-F]{64}")) &&
                    digest.equals(expectedApkSha256, true)) { "PRIMARY_CORE_APK_HASH" }
                val worker = ClientPrimaryWorker(app, choiceId, generation, platform, serviceClass, lease, onDeath)
                check(slots.putIfAbsent(serviceClass.name, worker) == null) { "PRIMARY_WORKER_COMPONENT_BUSY" }
                session = worker
                worker.bindAndInitialize(digest)
                return worker
            } catch (error: Throwable) {
                val worker = session
                if (worker == null) lease.close()
                else if (worker.handshake) {
                    // Preflight/editor callers have no external resource bundle
                    // to adopt a failed connect's owner. Keep it on this stack
                    // and in the shared barrier until real death is observed.
                    val retirement=ClientPrimaryRetirement(worker,
                        requestStop={worker.requestStop()},
                        retireCallbacks={(platform as? ClientPrimaryPlatformLifetime)?.close()},
                        closeOwner={it.close()},dependents=emptyList(),completed={})
                    var interrupted=Thread.interrupted()
                    try {
                        while(!retirement.attempt()){
                            try{Thread.sleep(retirement.retryDelayMillis())}catch(_:InterruptedException){interrupted=true}
                        }
                    }finally{if(interrupted)Thread.currentThread().interrupt()}
                }
                else worker.abandonBeforeHandshake()
                throw IllegalStateException(safeError(error.message))
            }
        }
    }
}
