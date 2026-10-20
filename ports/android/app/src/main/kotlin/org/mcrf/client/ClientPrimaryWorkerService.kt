package org.mcrf.client

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.engine.spi.v1.Platform
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bound, same-UID service. Main must declare each component in its own private process.
 * Binding alone never resolves or constructs a module. One owner and one native run per process.
 * No native exception text, native logs, profile, URL or configuration is sent to main.
 */
open class ClientPrimaryWorkerService : Service() {
    private val lifecycle = Executors.newSingleThreadExecutor { Thread(it, "mcrf-primary-run") }
    private val controls = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(16), { Thread(it, "mcrf-primary-control") })
    private val stopping = AtomicBoolean()
    private val retired = AtomicBoolean()
    private val reaperArmed = AtomicBoolean()
    private val claimed = AtomicBoolean()
    private val runClaimed = AtomicBoolean()
    private val lock = Any()
    @Volatile private var owner: IBinder? = null
    @Volatile private var ownerPid = -1
    @Volatile private var generation = 0L
    @Volatile private var engine: Engine? = null
    @Volatile private var packetBackend: org.mcrf.engine.protocol.v1.PacketBackend? = null
    private var packetRouteAdmission=PacketRouteEnvelope.Admission.LEGACY_IP
    private var packetManagedDnsAdmission=false
    @Volatile private var packetDns:PacketManagedDnsRuntime?=null
    @Volatile private var platform: Platform? = null
    @Volatile private var running = false
    private var core: ClientPrimaryCorePolicy.Core? = null
    // Intentionally retained until process death; the OS releases the worker's shared lock.
    private var usage: EngineUpdateGate.Usage? = null
    private var providedTun: ParcelFileDescriptor? = null
    private var stateDirectory: File? = null
    private var runReceiver: ResultReceiver? = null
    private val runningSent = AtomicBoolean()
    private val ownerDeath = IBinder.DeathRecipient { stopWorker() }

    override fun onCreate() {
        super.onCreate()
        val component = android.content.ComponentName(this, javaClass)
        val info = packageManager.getServiceInfo(component, 0)
        val expected = ClientPrimaryWorkerWire.processSuffix(javaClass)
        check(!info.exported && info.applicationInfo.uid == Process.myUid() &&
            info.processName == packageName + expected && Application.getProcessName() == info.processName &&
            processClaim.compareAndSet(false, true)) { "PRIMARY_WORKER_PRIVATE_PROCESS_REQUIRED" }
    }

    override fun onBind(intent: Intent?): IBinder = endpoint
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onUnbind(intent: Intent?): Boolean { stopWorker(); return false }
    override fun onDestroy() { stopWorker(); super.onDestroy() }

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(ClientPrimaryWorkerWire.DESCRIPTOR); return true }
            if (code != ClientPrimaryWorkerWire.TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != Process.myUid() || Binder.getCallingPid() == Process.myPid())
                throw SecurityException("PRIMARY_WORKER_UID")
            require(data.dataSize() <= 64 * 1024) { "PRIMARY_WORKER_IPC_SIZE" }
            data.enforceInterface(ClientPrimaryWorkerWire.DESCRIPTOR)
            val caller = Binder.getCallingPid()
            val input = data.readBundle(javaClass.classLoader) ?: throw IllegalArgumentException("PRIMARY_WORKER_IPC")
            if (input.getString("command") == "BIND") {
                check(flags and IBinder.FLAG_ONEWAY == 0 && caller > 0) { "PRIMARY_WORKER_HANDSHAKE_SYNC_REQUIRED" }
                val host = input.getBinder("owner") ?: throw IllegalArgumentException("PRIMARY_WORKER_OWNER")
                val gen = input.getLong("generation")
                synchronized(lock) {
                    check(gen > 0 && !stopping.get() && claimed.compareAndSet(false, true)) { "PRIMARY_WORKER_FRESH_REQUIRED" }
                    owner = host; ownerPid = caller; generation = gen
                    try { host.linkToDeath(ownerDeath, 0) } catch (_: Exception) { stopWorker(); throw IllegalStateException("PRIMARY_WORKER_OWNER_DEAD") }
                }
                reply?.writeNoException()
                reply?.writeBundle(Bundle().apply {
                    putInt("pid", Process.myPid()); putInt("uid", Process.myUid())
                    putString("process", Application.getProcessName()); putLong("generation", gen)
                    putString("identity", "private-binder-self-v1")
                    putString("birth", ClientPrimaryIdentityProof.parseBirth(java.io.File("/proc/self/stat").readText()))
                })
                return true
            }
            // getCallingPid() is zero for FLAG_ONEWAY; never weaken this to a PID==0
            // exception alone. Require the exact Binder capability plus generation and UID.
            check(ClientPrimaryWorkerWire.commandOwner(caller,ownerPid,flags and IBinder.FLAG_ONEWAY != 0,
                owner != null && input.getBinder("owner") == owner,input.getLong("generation"),generation)) { "PRIMARY_WORKER_OWNER" }
            val receiver = input.getParcelable<ResultReceiver>("receiver")
                ?: throw IllegalArgumentException("PRIMARY_WORKER_RECEIVER")
            if (input.getString("command") == "STOP") {
                stopWorker()
                respond(receiver, Bundle().apply { putString("status", "STOP_REQUESTED") })
                return true
            }
            if (stopping.get()) { fail(receiver, "PRIMARY_WORKER_STOPPING"); return true }
            // Binder threads only authenticate/enqueue; slow native work never occupies them.
            val command = input.getString("command")
            val task = Runnable {
                try {
                    check(!stopping.get()) { "PRIMARY_WORKER_STOPPING" }
                    when (command) {
                        "INIT" -> initialize(input, receiver)
                        "INIT_PACKET" -> initializePacket(input, receiver)
                        "START_PACKET" -> runPacket(input, receiver)
                        "VALIDATE" -> validateInput(input, receiver)
                        "PLAN" -> planInput(input, receiver)
                        "START" -> runInput(input, receiver)
                        else -> control(input, receiver)
                    }
                } catch (error: Throwable) {
                    debugFailure(command.orEmpty(),error)
                    fail(receiver, if (error is org.mcrf.engine.protocol.v1.PacketBackend.InvalidTun) "PACKET_NATIVE_TUN_REJECTED"
                        else if (command in setOf("INIT", "INIT_PACKET")) "PRIMARY_CORE_LOAD_FAILED" else "PRIMARY_WORKER_COMMAND_FAILED")
                    if (command in setOf("INIT", "START", "INIT_PACKET", "START_PACKET")) stopWorker()
                } finally {
                    // A rejected or malformed START must close its received duplicate too.
                    if (command in setOf("START", "START_PACKET")) input.getParcelable<ParcelFileDescriptor>("tun")?.let { try { it.close() } catch (_: Exception) {} }
                }
            }
            try {
                if (command in setOf("INIT", "VALIDATE", "PLAN", "START", "INIT_PACKET", "START_PACKET")) lifecycle.execute(task) else controls.execute(task)
            } catch (_: RuntimeException) {
                input.getParcelable<ParcelFileDescriptor>("tun")?.let { try { it.close() } catch (_: Exception) {} }
                fail(receiver, "PRIMARY_WORKER_BUSY")
            }
            return true
        }
    }

    private fun initialize(input: Bundle, receiver: ResultReceiver) {
        check(engine == null && packetBackend == null && core == null && !runClaimed.get()) { "PRIMARY_WORKER_FRESH_REQUIRED" }
        usage = EngineUpdateGate.acquireUsage(this) ?: throw IllegalStateException("PRIMARY_CORE_UPDATE_BUSY")
        val selected = ClientPrimaryCorePolicy.Core.parse(input.getString("choice").orEmpty())
        val contract = EngineUpdatePrimary.contract(selected.kind)
        EngineUpdatePrimary.installation(this, selected.kind)
        val resolved = ClientPrimaryCoreLoader.resolve(this, selected, contract.api, input.getString("apkSha256").orEmpty())
        // Loader checks sing-box's JNI classes. Apply the same child identity requirement to Steer.
        if (selected == ClientPrimaryCorePolicy.Core.STEER)
            check(Class.forName("org.steer.core.SteerCore", false, resolved.loader).classLoader === resolved.loader) { "PRIMARY_CORE_NATIVE_CLASSLOADER" }
        core = selected
        engine = ClientPrimaryCoreLoader.instantiate(resolved, this) as Engine
        input.getBinder("platform")?.let { platform = ClientPrimaryPlatformBinder.proxy(it, generation) }
        check(!stopping.get()) { "PRIMARY_WORKER_STOPPING" }
        respond(receiver, Bundle().apply { putLong("version", resolved.version) })
    }

    private fun initializePacket(input: Bundle, receiver: ResultReceiver) {
        check(engine == null && packetBackend == null && core == null && usage == null && !runClaimed.get()) { "PACKET_WORKER_FRESH_REQUIRED" }
        usage = EngineUpdateGate.acquireUsage(this) ?: error("PACKET_BACKEND_UPDATE_BUSY")
        // Explicit candidate command only; not selected by primary chooser or any profile fallback.
        val expectedPin=input.getString("apkSha256").orEmpty()
        val captured=EngineUpdatePacket.capture(this)
        require(captured.apkSha256==expectedPin){"PACKET_MODULE_PIN_CHANGED"}
        packetBackend = ClientPacketBackendLoader.load(this,expectedPin)
        packetRouteAdmission=captured.admission
        packetManagedDnsAdmission=captured.managedDns
        input.getBinder("platform")?.let { platform = ClientPrimaryPlatformBinder.proxy(it, generation) }
        check(!stopping.get()) { "PACKET_WORKER_STOPPING" }
        respond(receiver, Bundle().apply { putString("status", "PACKET_INITIALIZED") })
    }

    private fun runPacket(input: Bundle, receiver: ResultReceiver) {
        val backend = requireNotNull(packetBackend)
        val hostPlatform = requireNotNull(platform) { "PACKET_PLATFORM_REQUIRED" }
        check(engine == null && core == null && runClaimed.compareAndSet(false, true)) { "PACKET_WORKER_ONE_RUN" }
        val payload = ClientPrimaryWorkerWire.readInput(this, input.getString("file").orEmpty())
        // Recompute the exact TUN/peer projection from the original profile and ordered
        // IP rules. Unknown/richer fields and unaccepted captured BLOCK fail closed.
        val originalPlan = CompactWireGuardPlan.prepare(WireGuardProfile.parse(payload.getString("profile")), payload.getString("dial"))
        val settings=payload.getString("settings")
        require(input.get("ownedBlockFixture") is Boolean) {"PACKET_BLOCK_FIXTURE_FLAG"}
        val ownedBlockFixture=input.getBoolean("ownedBlockFixture")
        require(!ownedBlockFixture||BuildConfig.DEBUG) {"PACKET_BLOCK_FIXTURE_DEBUG_ONLY"}
        val plan = if(ownedBlockFixture) PacketRouteEnvelope.ownedBlockFixturePlan(originalPlan,settings)
            else PacketRouteEnvelope.workerPlan(originalPlan,settings,packetRouteAdmission,packetManagedDnsAdmission)
        providedTun = requireNotNull(input.getParcelable<ParcelFileDescriptor>("tun")) { "PACKET_TUN_REQUIRED" }
        val request=org.mcrf.engine.protocol.v1.PacketBackend.Request(generation,requireNotNull(providedTun).fd,plan.mtu,plan.secretConfiguration())
        val packetHost=object : org.mcrf.engine.protocol.v1.PacketBackend.Host {
            override fun stopping(gen: Long) = gen != generation || stopping.get() || owner?.isBinderAlive != true
            override fun prepareSocket(gen: Long, fd: Int) = !stopping(gen) && hostPlatform.protectAndBind(gen, fd) && !stopping(gen)
        }
        val captured=PacketRouteEnvelope.decode(settings,originalPlan.routes,originalPlan.addresses)
        val handle=payload.getLong("vpnNetworkHandle")
        if(captured.managed!=null){
            require(packetManagedDnsAdmission&&!ownedBlockFixture&&handle>0){"PACKET_MANAGED_DNS_NOT_ADMITTED"}
            val managed=backend as? org.mcrf.engine.protocol.dns.v1.ManagedDnsPacketBackend ?:error("PACKET_DNS_EXTENSION_MISSING")
            val network=android.net.Network.fromNetworkHandle(handle)
            val dns=PacketManagedDnsRuntime(getSystemService(android.net.ConnectivityManager::class.java),network,generation,originalPlan,captured,hostPlatform,managed){packetHost.stopping(generation)}
            packetDns=dns
            if(packetHost.stopping(generation)){dns.close();error("PACKET_WORKER_STOPPING")}
            managed.startManaged(request,packetHost,dns.config);dns.start()
        }else{require(handle==0L){"PACKET_UNEXPECTED_VPN_HANDLE"};backend.start(request,packetHost)}
        check(!stopping.get()) { "PACKET_WORKER_STOPPING" }
        respond(receiver, Bundle().apply {
            putString("status", "PACKET_RUNNING")
            putString("routeSettingsSha256",ClientPrimaryWorkerWire.sha256(settings.toByteArray(Charsets.UTF_8)))
            putBoolean("ownedBlockFixture",ownedBlockFixture)
            putBoolean("managedDns",captured.managed!=null)
            putLong("vpnNetworkHandle",handle)
        })
    }

    private fun validated(profile: String, settings: String, mode: Engine.Mode): Pair<Engine.Validation, String?> {
        val selected = requireNotNull(core)
        val native = requireNotNull(engine)
        val acquisition = ClientPrimaryWorkerWire.acquisition(selected, mode)
        var capabilityHash: String? = null
        if (selected == ClientPrimaryCorePolicy.Core.SINGBOX) {
            ClientPrimaryWorkerWire.checkSingBoxEnvelope(settings, mode)
            val capabilities = native.capabilitiesJson()
            check(capabilities.toByteArray(Charsets.UTF_8).size <= 65536) { "PRIMARY_CORE_CAPABILITY_SIZE" }
            val cap = JSONObject(capabilities)
            check(cap.getInt("schema") == 1 && cap.getString("kind") == "sing-box" &&
                cap.getInt("spi") == 1 && cap.getInt("coreApi") == 1 && cap.getBoolean("fullNativeConfig") &&
                cap.getBoolean("oneRunPerProcess") && cap.getString("tunPlanMode") == "deferred-native-options" &&
                (mode != Engine.Mode.PROXY || cap.getBoolean("proxyOnly"))) { "PRIMARY_CORE_CAPABILITIES" }
            capabilityHash = ClientPrimaryWorkerWire.sha256(capabilities.toByteArray(Charsets.UTF_8))
        }
        val result = native.validate(profile, settings, mode, acquisition)
        if (result.supported) {
            check(result.mode == mode && result.tunAcquisition == acquisition) { "PRIMARY_CORE_VALIDATION_MODE" }
            if (selected == ClientPrimaryCorePolicy.Core.SINGBOX) {
                // Request verifies private validation input/capability fingerprints, including proxy mode.
                Engine.Request(generation, -1, settings, profile, "", cacheDir.path, 0, emptyArray(), mode,
                    result, acquisition, capabilityHash)
            }
        }
        return result to capabilityHash
    }

    private fun validateInput(input: Bundle, receiver: ResultReceiver) {
        check(!runClaimed.get()) { "PRIMARY_WORKER_ALREADY_RUN" }
        val payload = ClientPrimaryWorkerWire.readInput(this, input.getString("file").orEmpty())
        val mode = Engine.Mode.valueOf(input.getString("mode").orEmpty())
        val checked = try { validated(payload.getString("profile"), payload.getString("settings"), mode) }
            catch (_: Throwable) { Engine.Validation(false, "PRIMARY_CORE_CONFIG_REJECTED", "config") to null }
        val result = checked.first
        respond(receiver, Bundle().apply {
            putBoolean("supported", result.supported)
            putString("code", if (result.supported) "" else "PRIMARY_CORE_CONFIG_REJECTED")
            putString("field", if (result.supported) "" else "config")
            putString("mode", mode.name)
            putString("acquisition", ClientPrimaryWorkerWire.acquisition(requireNotNull(core), mode).name)
            // Hash only, never config or arbitrary native diagnostics.
            if (result.supported && core == ClientPrimaryCorePolicy.Core.SINGBOX && mode == Engine.Mode.VPN)
                putString("capabilitySha256", checked.second)
        })
    }

    private fun planInput(input: Bundle, receiver: ResultReceiver) {
        check(!runClaimed.get()) { "PRIMARY_WORKER_ALREADY_RUN" }
        val payload = ClientPrimaryWorkerWire.readInput(this, input.getString("file").orEmpty())
        val mode = Engine.Mode.valueOf(input.getString("mode").orEmpty())
        val profile = payload.getString("profile"); val settings = payload.getString("settings")
        check(validated(profile, settings, mode).first.supported) { "PRIMARY_CORE_CONFIG_REJECTED" }
        val plan = requireNotNull(engine).tunPlanJson(profile, settings)
        ClientPrimaryWorkerWire.writeOutput(this, input.getString("output").orEmpty(), plan)
        respond(receiver, Bundle())
    }

    private fun runInput(input: Bundle, receiver: ResultReceiver) {
        check(runClaimed.compareAndSet(false, true)) { "PRIMARY_WORKER_ALREADY_RUN" }
        runReceiver = receiver
        try {
            val hostPlatform = requireNotNull(platform) { "PRIMARY_WORKER_PLATFORM_REQUIRED" }
            val payload = ClientPrimaryWorkerWire.readInput(this, input.getString("file").orEmpty())
            val mode = Engine.Mode.valueOf(input.getString("mode").orEmpty())
            val profile = payload.getString("profile"); val settings = payload.getString("settings")
            val (validation, hash) = validated(profile, settings, mode)
            check(validation.supported) { "PRIMARY_CORE_CONFIG_REJECTED" }
            val acquisition = ClientPrimaryWorkerWire.acquisition(requireNotNull(core), mode)
            providedTun = input.getParcelable("tun")
            check((acquisition == Engine.TunAcquisition.PROVIDED) == (providedTun != null)) { "PRIMARY_WORKER_TUN_MODE" }
            val handle = payload.getLong("vpnNetworkHandle")
            check(acquisition == Engine.TunAcquisition.PROVIDED || handle == 0L) { "PRIMARY_WORKER_TUN_MODE" }
            check(core != ClientPrimaryCorePolicy.Core.STEER || handle > 0L) { "PRIMARY_WORKER_VPN_NETWORK_REQUIRED" }
            val bootstrap = payload.getJSONArray("dnsBootstrap")
            check(bootstrap.length() <= 8) { "PRIMARY_WORKER_BOOTSTRAP" }
            val dns = Array(bootstrap.length()) { bootstrap.getString(it) }
            val directory = ClientPrimaryWorkerWire.runDirectory(this, input.getString("stateDirectory").orEmpty()).also { stateDirectory = it }
            val request = Engine.Request(generation, providedTun?.fd ?: -1, settings, profile,
                payload.getString("dial"), directory.path, handle, dns, mode, validation, acquisition, hash)
            check(!stopping.get()) { "PRIMARY_WORKER_STOPPING" }
            requireNotNull(engine).run(request, object : Engine.Host {
                override fun prepareSocket(gen: Long, fd: Int): Boolean =
                    !stopping(gen) && fd >= 0 && try { hostPlatform.protectAndBind(gen, fd) } catch (_: Exception) { false }
                override fun state(gen: Long, state: String, reason: String) {
                    if (gen != generation || stopping.get()) return
                    val accepted = ClientPrimaryWorkerWire.acknowledgesStart(requireNotNull(core), state)
                    val steerPort = if (accepted && core == ClientPrimaryCorePolicy.Core.STEER)
                        ClientSteerStartMetadata.moduleReason(reason, JSONObject(settings)) else null
                    if (accepted && runningSent.compareAndSet(false, true)) {
                        running = true
                        respond(receiver, Bundle().apply { putString("status", state); if (steerPort != null) putInt("steerProxyPort", steerPort) })
                    } else if (state in setOf("ERROR", "FAILED")) {
                        fail(receiver, "PRIMARY_CORE_RUN_FAILED"); stopWorker()
                    }
                }
                override fun stopping(gen: Long): Boolean = gen != generation || stopping.get() || owner?.isBinderAlive != true
                override fun packageName(): String = this@ClientPrimaryWorkerService.packageName
                override fun platform(): Platform = hostPlatform
            })
        } catch (error: Throwable) {
            debugFailure("RUN",error)
            fail(receiver, "PRIMARY_CORE_RUN_FAILED")
        } finally {
            running = false; stopping.set(true)
            if (!runningSent.get()) fail(receiver, "PRIMARY_CORE_EXITED_BEFORE_RUNNING")
            armReaper()
            retireWorker()
        }
    }

    private fun control(input: Bundle, receiver: ResultReceiver) {
        check(running && !stopping.get()) { "PRIMARY_WORKER_NOT_RUNNING" }
        val native = requireNotNull(engine)
        val result = Bundle()
        when (input.getString("command")) {
            "STATS" -> {
                val stats = native.stats()
                stats.txPayload.value?.let { result.putLong("tx", it) }
                stats.rxPayload.value?.let { result.putLong("rx", it) }
                stats.blockedAttempts.value?.let { result.putLong("blocked", it) }
            }
            "HEALTH", "RESET", "SELECT", "SELECTED" -> {
                check(core == ClientPrimaryCorePolicy.Core.SINGBOX && native.javaClass.name == ClientPrimaryCorePolicy.Core.SINGBOX.entryClass) { "PRIMARY_CORE_CONTROL_UNSUPPORTED" }
                fun invoke(name: String, types: Array<Class<*>>, vararg args: Any): Any? {
                    val method = native.javaClass.getMethod(name, *types)
                    check(method.declaringClass === native.javaClass) { "PRIMARY_CORE_CONTROL_IDENTITY" }
                    return method.invoke(native, *args)
                }
                when (input.getString("command")) {
                    "HEALTH" -> {
                        val tag = ClientPrimaryWorkerWire.smallString(input.getString("tag"), 512)
                        val url = ClientPrimaryWorkerWire.smallString(input.getString("url"), 8192)
                        val timeout = input.getInt("timeout")
                        check(timeout in 1..30000) { "PRIMARY_WORKER_TIMEOUT" }
                        val value = invoke("urlTest", arrayOf(String::class.java, String::class.java, Int::class.javaPrimitiveType!!), tag, url, timeout)
                        check(value is Long && value >= -1L) { "PRIMARY_CORE_HEALTH_RESULT" }
                        result.putLong("latency", value.toLong())
                    }
                    "RESET" -> invoke("resetNetwork", emptyArray())
                    "SELECT" -> {
                        val group = ClientPrimaryWorkerWire.smallString(input.getString("group"), 512)
                        val tag = ClientPrimaryWorkerWire.smallString(input.getString("tag"), 512)
                        val value = invoke("selectOutbound", arrayOf(String::class.java, String::class.java), group, tag)
                        check(value != false) { "PRIMARY_CORE_SELECT_REJECTED" }
                    }
                    "SELECTED" -> {
                        val group = ClientPrimaryWorkerWire.smallString(input.getString("group"), 512)
                        val value = invoke("selectedOutbound", arrayOf(String::class.java), group)
                        result.putString("tag", ClientPrimaryWorkerWire.smallString(value as? String, 512))
                    }
                }
            }
            else -> throw IllegalArgumentException("PRIMARY_WORKER_COMMAND")
        }
        check(!stopping.get()) { "PRIMARY_WORKER_STOPPING" }
        respond(receiver, result)
    }

    private fun stopWorker() {
        armReaper()
        if (!stopping.compareAndSet(false, true)) return
        // Dedicated stop lane cannot be starved by slow concurrent URL tests.
        Thread({ try { packetDns?.close() } catch (_: Throwable) {};try { packetBackend?.close() } catch (_: Throwable) {}; try { engine?.requestStop() } catch (_: Throwable) {} }, "mcrf-primary-stop").start()
        try { lifecycle.execute { retireWorker() } } catch (_: RuntimeException) {}
    }

    private fun armReaper() {
        if (!reaperArmed.compareAndSet(false, true)) return
        // Owner death/unbind must also terminate a core hung in construction, validation or JNI.
        Thread({
            try { Thread.sleep(5000) } catch (_: InterruptedException) {}
            // While main is alive its verified-PID timeout owns forced termination. A self-kill
            // normally occurs only after finally below; owner death needs this orphan backstop.
            if (owner?.isBinderAlive != true) killWorker()
        }, "mcrf-primary-reaper").apply { isDaemon = true; start() }
    }

    private fun retireWorker() {
        if (!retired.compareAndSet(false, true)) return
        try {
            try { packetDns?.close() } catch (_: Throwable) {}
            try { packetBackend?.close() } catch (_: Throwable) {}
            try { engine?.requestStop() } finally {
                platform?.let { ClientPrimaryPlatformBinder.closeProxy(it) }
            }
        } catch (_: Throwable) {
            // Process death is still mandatory even if native/transport cleanup failed.
        } finally {
            platform = null; engine = null; packetBackend = null; packetDns=null;packetRouteAdmission=PacketRouteEnvelope.Admission.LEGACY_IP;packetManagedDnsAdmission=false
            try { providedTun?.close() } catch (_: Exception) {}; providedTun = null
            stateDirectory?.let { ClientPrimaryWorkerWire.removeRunDirectory(this, it) }; stateDirectory = null
            controls.shutdownNow(); lifecycle.shutdown()
            runReceiver?.let { fail(it, "PRIMARY_WORKER_STOPPED") }; runReceiver = null
            // Never ACK termination here: main must observe actual Binder death before releasing its lease.
            stopSelf()
            killWorker()
        }
    }

    private fun killWorker() {
        // Defense in depth against a missing/incorrect manifest process assignment: never kill UI.
        if (Application.getProcessName() == packageName + ClientPrimaryWorkerWire.processSuffix(javaClass) &&
            Process.myPid() != ownerPid) Process.killProcess(Process.myPid())
    }

    private fun respond(receiver: ResultReceiver, data: Bundle) {
        data.putLong("generation", generation)
        try { receiver.send(0, data) } catch (_: Exception) { stopWorker() }
    }
    private fun fail(receiver: ResultReceiver, code: String) = respond(receiver, Bundle().apply { putString("error", code) })

    private fun debugFailure(phase:String,error:Throwable) {
        if(!BuildConfig.DEBUG)return
        // Do not pass Throwable to Log: native messages can contain full secret profiles.
        // Only fixed phase, exception class and code locations are allowed, never messages.
        val allowedPhase=phase.takeIf {it in setOf("INIT","VALIDATE","PLAN","START","RUN","HEALTH","STATS","RESET","SELECT","SELECTED") }?:"COMMAND"
        val chain=generateSequence(error){it.cause}.take(4).joinToString(" | "){cause->
            cause.javaClass.name+":"+cause.stackTrace.take(5).joinToString(","){frame->
                "${frame.className}.${frame.methodName}:${frame.lineNumber}"
            }
        }
        android.util.Log.w("MCRF.Primary","$allowedPhase $chain")
    }

    companion object { private val processClaim = AtomicBoolean() }
}

/** Main declares :primaryProbe and :primaryWarp; no module code belongs in these components. */
class ClientPrimaryProbeWorkerService : ClientPrimaryWorkerService()
class ClientPrimaryWarpWorkerService : ClientPrimaryWorkerService()

/** Private IPC: only small control DTOs cross Binder; complete run inputs travel in 0600 cache files. */
internal object ClientPrimaryWorkerWire {
    const val DESCRIPTOR = "org.mcrf.client.primary.worker.v1"
    const val TRANSACTION = IBinder.FIRST_CALL_TRANSACTION
    const val MAX_INPUT = 4 * 1024 * 1024
    const val MAX_PLAN = 256 * 1024
    private val inputName = Regex("mcrf-primary-input-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.json")
    private val outputName = Regex("mcrf-primary-output-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.json")
    private val runName = Regex("mcrf-run-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    fun processSuffix(type: Class<out ClientPrimaryWorkerService>): String = when (type) {
        ClientPrimaryWorkerService::class.java -> ":primary"
        ClientPrimaryProbeWorkerService::class.java -> ":primaryProbe"
        ClientPrimaryWarpWorkerService::class.java -> ":primaryWarp"
        else -> throw IllegalArgumentException("PRIMARY_WORKER_COMPONENT")
    }
    fun acquisition(core: ClientPrimaryCorePolicy.Core, mode: Engine.Mode): Engine.TunAcquisition = when {
        mode == Engine.Mode.PROXY -> Engine.TunAcquisition.NONE
        core == ClientPrimaryCorePolicy.Core.SINGBOX -> Engine.TunAcquisition.DEFERRED
        else -> Engine.TunAcquisition.PROVIDED
    }
    fun acknowledgesStart(core: ClientPrimaryCorePolicy.Core, state: String?): Boolean =
        state == "RUNNING" || (core == ClientPrimaryCorePolicy.Core.STEER && state == "STARTING")
    /** Same-UID is checked at the Binder endpoint before this capability/generation policy. */
    fun commandOwner(callerPid:Int,ownerPid:Int,oneway:Boolean,tokenMatches:Boolean,generation:Long,expected:Long):Boolean =
        ownerPid>0 && tokenMatches && generation>0 && generation==expected &&
            (callerPid==ownerPid || callerPid==0 && oneway)
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun hashFile(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun smallString(value: String?, cap: Int): String = requireNotNull(value).also {
        require(it.length <= cap && it.none { char -> Character.isISOControl(char) }) { "PRIMARY_WORKER_STRING" }
    }
    fun checkSingBoxEnvelope(settings: String, mode: Engine.Mode) {
        check(settings.length <= MAX_INPUT) { "PRIMARY_CORE_CONFIG_SIZE" }
        val json = JSONObject(settings)
        check(json.keys().asSequence().toSet() == setOf("schema", "mode", "metered", "config") &&
            json.get("schema") is Int && json.getInt("schema") == 1 && json.get("metered") is Boolean &&
            json.get("config") is JSONObject && json.getString("mode") == if (mode == Engine.Mode.VPN) "vpn" else "proxy") { "PRIMARY_CORE_CONFIG_ENVELOPE" }
    }
    fun writeInput(context: Context, payload: JSONObject): File {
        val text = payload.toString()
        require(text.length <= MAX_INPUT) { "PRIMARY_WORKER_INPUT_SIZE" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_INPUT) { "PRIMARY_WORKER_INPUT_SIZE" }
        val base = context.cacheDir.canonicalFile
        val file = File(base, "mcrf-primary-input-${UUID.randomUUID()}.json")
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        try {
            FileOutputStream(fd).use { Os.fchmod(fd, 0x180); it.write(bytes); it.fd.sync() }
        } catch (_: Exception) { removeInput(context, file); throw IllegalStateException("PRIMARY_WORKER_INPUT_WRITE") }
        return file
    }
    fun createOutput(context: Context): File {
        val file = File(context.cacheDir.canonicalFile, "mcrf-primary-output-${UUID.randomUUID()}.json")
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
        try { Os.fchmod(fd, 0x180) } catch (_: Exception) {
            removeInput(context, file); throw IllegalStateException("PRIMARY_WORKER_OUTPUT_WRITE")
        } finally { Os.close(fd) }
        return file
    }
    private fun outputFile(context: Context, path: String): File = File(path).also {
        require(outputName.matches(it.name) && it.absoluteFile.parentFile == context.cacheDir.canonicalFile &&
            it.canonicalFile == it.absoluteFile && !Files.isSymbolicLink(it.toPath())) { "PRIMARY_WORKER_OUTPUT_PATH" }
    }
    fun writeOutput(context: Context, path: String, text: String) {
        require(text.length <= MAX_PLAN) { "PRIMARY_WORKER_PLAN_SIZE" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_PLAN) { "PRIMARY_WORKER_PLAN_SIZE" }
        val file = outputFile(context, path)
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        FileOutputStream(fd).use { output ->
            val stat = Os.fstat(fd)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                stat.st_mode and 0x1ff == 0x180 && stat.st_size == 0L) { "PRIMARY_WORKER_OUTPUT_FILE" }
            output.write(bytes); output.fd.sync()
        }
    }
    fun readOutput(context: Context, file: File): String {
        outputFile(context, file.path)
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        val bytes = FileInputStream(fd).use { input ->
            val stat = Os.fstat(fd)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                stat.st_mode and 0x1ff == 0x180 && stat.st_size in 1..MAX_PLAN.toLong()) { "PRIMARY_WORKER_OUTPUT_FILE" }
            val bytes = ByteArray(stat.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) { val n = input.read(bytes, offset, bytes.size - offset); require(n > 0); offset += n }
            require(input.read() == -1) { "PRIMARY_WORKER_PLAN_SIZE" }
            bytes
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    fun readInput(context: Context, path: String): JSONObject {
        val base = context.cacheDir.canonicalFile
        val file = File(path)
        require(inputName.matches(file.name) && file.absoluteFile.parentFile == base &&
            file.canonicalFile == file.absoluteFile && !Files.isSymbolicLink(file.toPath())) { "PRIMARY_WORKER_INPUT_PATH" }
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        val bytes = try {
            val stat = Os.fstat(fd)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                stat.st_mode and 0x1ff == 0x180 && stat.st_size in 1..MAX_INPUT.toLong()) { "PRIMARY_WORKER_INPUT_FILE" }
            FileInputStream(fd).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= MAX_INPUT) { "PRIMARY_WORKER_INPUT_SIZE" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        } catch (error: Throwable) { try { Os.close(fd) } catch (_: Exception) {}; throw error }
        finally { removeInput(context, file) }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return JSONObject(text).also {
            require(it.keys().asSequence().toSet() == setOf("profile", "settings", "dial", "dnsBootstrap", "vpnNetworkHandle")) { "PRIMARY_WORKER_INPUT_FIELDS" }
        }
    }
    fun removeInput(context: Context, file: File) {
        try {
            if ((inputName.matches(file.name) || outputName.matches(file.name)) && file.absoluteFile.parentFile == context.cacheDir.canonicalFile &&
                file.canonicalFile == file.absoluteFile && !Files.isSymbolicLink(file.toPath())) file.delete()
        } catch (_: Exception) {}
    }
    fun makeRunDirectory(context: Context): File = File(context.cacheDir.canonicalFile, "mcrf-run-${UUID.randomUUID()}").also {
        require(it.mkdir() && it.canonicalFile == it.absoluteFile) { "PRIMARY_WORKER_STATE_DIRECTORY" }
        try { Os.chmod(it.path, 0x1c0) } catch (_: Exception) {
            it.delete(); throw IllegalStateException("PRIMARY_WORKER_STATE_DIRECTORY")
        }
    }
    fun runDirectory(context: Context, path: String): File = File(path).also {
        require(runName.matches(it.name) && it.absoluteFile.parentFile == context.cacheDir.canonicalFile &&
            it.canonicalFile == it.absoluteFile && !Files.isSymbolicLink(it.toPath())) { "PRIMARY_WORKER_STATE_DIRECTORY" }
        val stat = Os.lstat(it.path)
        require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() &&
            stat.st_mode and 0x1ff == 0x1c0) { "PRIMARY_WORKER_STATE_DIRECTORY" }
    }
    fun removeRunDirectory(context: Context, root: File) {
        try {
            if (!runName.matches(root.name) || root.absoluteFile.parentFile != context.cacheDir.canonicalFile ||
                root.canonicalFile != root.absoluteFile || Files.isSymbolicLink(root.toPath())) return
            // Do not follow native-created symlinks. walkFileTree's default never follows links.
            Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        } catch (_: Exception) {}
    }
}
