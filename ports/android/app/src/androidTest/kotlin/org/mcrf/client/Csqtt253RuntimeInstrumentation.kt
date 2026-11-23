package org.mcrf.client

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** ONE provider-bearing attempt, never a benchmark or a version/hash retry loop.
 * Main MUST establish a no-VPN safe window BEFORE am instrument: Android may
 * force-stop the target before any code here can check it. Required string args:
 * safeWindowConfirmed=1, cooldownConfirmed=1, allowOneStart=1; optional workers=100.
 * No profile/selection is saved. Default requires the saved effective count=80.
 * NativeCarrier still owns credentials, shared cooldown and its own retry policy.
 *
 * Active work ends at +25s, reserving 35s within the 60s acceptance budget.
 * Retirement is fail-safe: NativeCarrier.close() must CONFIRM group death. Its
 * existing unbounded retirement API can exceed 60s on an OS/cleanup failure;
 * never abandon a living child, unlock its module or report timely success.
 */
class Csqtt253RuntimeInstrumentation : Instrumentation() {
    private enum class Status {
        UNAVAILABLE, INVALID_ARGUMENTS, STARTUP_ISOLATION_UNAVAILABLE, STORE_UNAVAILABLE,
        USER_STORES_CHANGED, ACTIVE_VPN, SAFE_MODE, PROBE_BUSY, NO_NETWORK, NETWORK_CHANGED,
        MODULE_BUSY, NO_MANUAL_253_PROFILE, CONFIGURATION_REJECTED, COOLDOWN_BLOCKED,
        START_FAILED, WIRE_FAILED, NATIVE_FAILED, CANCELLED, DEADLINE,
        WIRE_OK_TELEMETRY_UNAVAILABLE, WIRE_OK_STREAMS_NOT_GT60, PASS_WIRE_AND_GT60
    }
    private class Halt(val status: Status) : Exception()
    private var input = Bundle()
    private var began = 0L
    private lateinit var app: Context
    private var baseline: Map<String, String>? = null
    private val cancelled = AtomicBoolean()
    private val networkToken = AtomicReference<DiagnosticCancellation?>()
    private val stopReason = AtomicReference<Status?>()
    private var networkHandle = 0L
    private val workDeadline get() = began + 25_000L

    /** Target ONLY the dedicated process (manifest targetProcesses). Android
     * installs providers BEFORE instrumentation.onCreate: fail before that if
     * the launcher binds us to MAIN or any provider is declared in our process.
     * Base Application adds defense in depth against unrelated app jobs;
     * it does NOT suppress native safety or provider cooldown enforcement.
     */
    @Suppress("DEPRECATION")
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        began = SystemClock.elapsedRealtime()
        app = context
        try {
            require(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            require(Application.getProcessName() == PROCESS && className == McrfApplication::class.java.name &&
                context.packageName == PACKAGE && context.applicationInfo.packageName == PACKAGE &&
                context.applicationInfo.processName == PACKAGE && context.applicationInfo.uid == android.os.Process.myUid())
            val providers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
            require(providers.none { it.processName == PROCESS })
            baseline = protectedHashes(began + 4_000)
            existingKey()
            // All registrations are in-memory validators; no restore/load/write.
            ClientSettingsRegistry.register()
            return super.newApplication(cl, Application::class.java.name, context)
        } catch (_: Exception) {
            // Fail BEFORE unreviewed/mutating providers, not after a baseline
            // reset. No exception cause, paths or profile bytes may escape.
            throw IllegalStateException(Status.STARTUP_ISOLATION_UNAVAILABLE.name)
        }
    }

    override fun onCreate(arguments: Bundle?) {
        input = arguments?.let(::Bundle) ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onDestroy() {
        cancelled.set(true)
        networkToken.get()?.cancel()
        super.onDestroy()
    }

    override fun onStart() {
        val output = JSONObject().put("status", Status.UNAVAILABLE.name).put("implementation", JSONObject.NULL)
        for (key in listOf("startupMs", "configuredWorkers", "actualActiveStreams", "httpStatus", "userStoresUnchanged", "childRetired"))
            output.put(key, JSONObject.NULL)
        var status = Status.UNAVAILABLE
        var owner: NativeCarrier? = null
        var channel: FileChannel? = null
        var lock: FileLock? = null
        var proxyLease: AtomicBoolean? = null
        var watcher: Thread? = null
        val watching = AtomicBoolean(true)
        var attempted = false
        var ownedProcess: Process? = null
        var retirementConfirmed = false
        try {
            demand(BuildConfig.DEBUG && Application.getProcessName() == PROCESS &&
                targetContext.applicationContext.javaClass == Application::class.java &&
                targetContext.applicationInfo.packageName == PACKAGE && targetContext.applicationInfo.processName == PACKAGE &&
                targetContext.applicationInfo.uid == android.os.Process.myUid(),
                Status.STARTUP_ISOLATION_UNAVAILABLE)
            demand(baseline != null && began > 0, Status.STARTUP_ISOLATION_UNAVAILABLE)
            demand(input.keySet().all { it in setOf("safeWindowConfirmed", "cooldownConfirmed", "allowOneStart", "workers") }
                && listOf("safeWindowConfirmed", "cooldownConfirmed", "allowOneStart").all { input.get(it) == "1" }
                && (!input.containsKey("workers") || input.get("workers") == "100"), Status.INVALID_ARGUMENTS)
            ensureIdle()
            demand(noOtherOwnedUidProcess(), Status.PROBE_BUSY)
            val network = DirectAccessCheck.physical(app) ?: throw Halt(Status.NO_NETWORK)
            networkHandle = network.networkHandle

            // Existing inode ONLY, exclusive rather than shared. Normal module
            // starts/updates/probes cannot acquire usage until this child retires.
            // Never create a gate, marker, key, preference or replacement inode.
            val gate = File(app.applicationInfo.dataDir, "no_backup/engine-update-gate")
            stable(gate); demand(gate.isDirectory, Status.MODULE_BUSY)
            val inode = File(gate, "usage.lock")
            stable(inode); demand(inode.isFile, Status.MODULE_BUSY)
            channel = FileChannel.open(inode.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
            lock = try { channel.tryLock() } catch (_: Exception) { null }
            demand(lock != null && !File(gate, "pending.json").exists(), Status.MODULE_BUSY)
            // Reuse the actual temporary-diagnostics single-flight admission;
            // release only our successful CAS, never cancel another probe.
            val leased = staticField(DiagnosticsTemporaryProxy::class.java, "leased") as AtomicBoolean
            demand(leased.compareAndSet(false, true), Status.PROBE_BUSY)
            proxyLease = leased

            val key = existingKey()
            val stores = storeDirectory()
            val preferences = encryptedJson(File(stores, "preferences.enc"), key)
            val entries = ConnectionCatalogue(encryptedJson(File(stores, "connections.enc"), key).toString()).entries()
            val eligible = entries.filter { candidate -> candidate.carrier?.let(::manual253) == true }
            val selected = readXml(File(app.applicationInfo.dataDir, "shared_prefs/MainActivity.xml"))["selected"]
            val entry = eligible.firstOrNull { it.id == selected } ?: eligible.firstOrNull()
                ?: throw Halt(Status.NO_MANUAL_253_PROFILE)
            val saved = requireNotNull(entry.carrier)
            val savedBytes = saved.toString()
            val clone = JSONObject(savedBytes)
            if (input.containsKey("workers")) clone.put(CarrierStreamDefaults.OVERRIDE, 100)
            // Avoid identity()'s first-use write; reuse ONLY the existing device
            // identity, never generate/substitute a new installation identifier.
            if (clone.optString("device_id").isEmpty()) {
                val identity = readXml(File(app.applicationInfo.dataDir, "shared_prefs/identity.xml"))["hwid"] as? String
                demand(!identity.isNullOrBlank(), Status.STORE_UNAVAILABLE)
                clone.put("device_id", identity)
            }
            val deploy = preferences.optJSONObject(AndroidDeployBackupSettings.SECTION) ?: JSONObject()
            val memory = object : VkRelayPersistence {
                override fun get(section: String) = JSONObject((deploy.optJSONObject(section) ?: JSONObject()).toString())
                override fun update(section: String, change: (JSONObject) -> Unit) { throw Halt(Status.USER_STORES_CHANGED) }
                override fun remove(section: String) { throw Halt(Status.USER_STORES_CHANGED) }
                override fun verifyCaptcha(url: String, cancelled: () -> Boolean): String = throw Halt(Status.CONFIGURATION_REJECTED)
            }
            val account = VkRelayAccount(memory, { throw Halt(Status.CONFIGURATION_REJECTED) }, { throw Halt(Status.CONFIGURATION_REJECTED) })
            val effective = CarrierStreamDefaults.apply(VkRelayRuntime.withManualSupplements(JSONObject(clone.toString()), account),
                preferences.optJSONObject(CarrierStreamDefaults.SECTION) ?: JSONObject())
            val configured = effective.getInt("workers")
            demand(configured == if (input.containsKey("workers")) 100 else 80, Status.CONFIGURATION_REJECTED)
            demand(saved.toString() == savedBytes && manual253(effective), Status.CONFIGURATION_REJECTED)
            demand(protectedHashes(minOf(workDeadline, SystemClock.elapsedRealtime() + 4_000)) == baseline, Status.USER_STORES_CHANGED)
            ensureIdle()
            // NativeCarrier calls the SAME shared host requireAllowed again,
            // and observes it during startup. Never expire/clear/bypass it here.
            VkCredentialBackoffStore(app).requireAllowed()
            checkWork()
            watcher = Thread({
                while (watching.get()) {
                    try {
                        ensureIdle()
                        if (SystemClock.elapsedRealtime() >= workDeadline) throw Halt(Status.DEADLINE)
                    } catch (error: Exception) {
                        stopReason.compareAndSet(null, (error as? Halt)?.status ?: Status.CANCELLED)
                        cancelled.set(true); networkToken.get()?.cancel(); break
                    }
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }, "csqtt253-fixture-cancel").apply { isDaemon = true; start() }
            val startBudget = minOf(18_000L, workDeadline - SystemClock.elapsedRealtime() - 4_000)
            demand(startBudget >= 1_000, Status.DEADLINE)
            status = Status.START_FAILED
            attempted = true
            val startAt = SystemClock.elapsedRealtime()
            // Exactly one call. clone still carries the real saved options and
            // override; NativeCarrier repeats the REAL merge/defaults order.
            owner = NativeCarrier.startBackground(app, JSONObject(clone.toString()), BooleanSupplier { cancelled.get() }, startBudget)
            output.put("startupMs", SystemClock.elapsedRealtime() - startAt)
            ownedProcess = NativeCarrier::class.java.getDeclaredField("process").apply { isAccessible = true }.get(owner) as Process
            demand(ownedProcess.isAlive, Status.NATIVE_FAILED)
            observeOwnedConfig(owner, configured)
            output.put("configuredWorkers", configured).put("implementation", IMPLEMENTATION)
            status = Status.WIRE_FAILED
            checkWork(); owner.checkFailure()
            proveHttps(owner)
            output.put("httpStatus", 204)
            status = Status.WIRE_OK_TELEMETRY_UNAVAILABLE
            var overSixtySamples = 0
            while (SystemClock.elapsedRealtime() < workDeadline - 250) {
                checkWork(); owner.checkFailure()
                demand(owner.runtimeFailure() == null, Status.NATIVE_FAILED)
                val active = owner.actualStreams()
                demand(active in -1..TurnStreams.MAX, Status.NATIVE_FAILED)
                output.put("actualActiveStreams", if (active < 0) JSONObject.NULL else active)
                status = if (active < 0) Status.WIRE_OK_TELEMETRY_UNAVAILABLE else Status.WIRE_OK_STREAMS_NOT_GT60
                overSixtySamples = if (active > 60) overSixtySamples + 1 else 0
                if (overSixtySamples >= 2) { status = Status.PASS_WIRE_AND_GT60; break }
                Thread.sleep(100) // Observe only; never start another connection.
            }
        } catch (error: Exception) {
            status = when (error) {
                is Halt -> error.status
                is VkCredentialLimit.Blocked -> Status.COOLDOWN_BLOCKED
                is InterruptedException -> Status.CANCELLED
                else -> stopReason.get() ?: status
            }
        } finally {
            watching.set(false); watcher?.interrupt()
            networkToken.getAndSet(null)?.cancel()
            // A late safety cancellation cannot be lost between the second
            // native sample and retirement, even if wire/count already passed.
            stopReason.get()?.let { status = it }
            if (cancelled.get() && status == Status.PASS_WIRE_AND_GT60) status = Status.CANCELLED
            // No global stop/kill, service start, directory sweep or borrowed
            // PID. startBackground's throwing path normally retires its local
            // child internally, but absence of a returned handle is NOT proof.
            // Keep module/probe leases until exact owned retirement completes.
            var interrupted = Thread.interrupted()
            if (owner != null) {
                while (!retirementConfirmed) {
                    try {
                        owner.close() // Includes CarrierProcessGroup.awaitDeath.
                        val complete = NativeCarrier::class.java.getDeclaredField("closeComplete")
                            .apply { isAccessible = true }.getBoolean(owner)
                        demand(complete && ownedProcess?.isAlive != true, Status.NATIVE_FAILED)
                        retirementConfirmed = true
                    }
                    catch (_: Exception) {
                        status = Status.NATIVE_FAILED
                        if (Thread.interrupted()) interrupted = true
                        try { Thread.sleep(100) } catch (_: InterruptedException) { interrupted = true }
                    }
                }
            } else if (attempted) {
                // No handle escaped a throwing start. Keep admission/usage
                // leases until a fail-closed owned-UID process inventory is quiet;
                // never infer retirement from a timeout or kill a guessed PID.
                // childRetired stays NULL: this is not exact-child evidence.
                while (!noOtherOwnedUidProcess()) {
                    status = Status.NATIVE_FAILED
                    try { Thread.sleep(100) } catch (_: InterruptedException) { interrupted = true }
                }
            }
            output.put("childRetired", if (owner != null && retirementConfirmed) 1 else JSONObject.NULL)
            // Retry release of OUR gate only. No failed release may let a
            // replacement start race an unconfirmed owned child/IPC writer.
            var released = false
            while (!released) {
                try {
                    if (lock?.isValid == true) lock.release()
                    channel?.close()
                    released = channel?.isOpen != true && lock?.isValid != true
                } catch (_: Exception) { status = Status.MODULE_BUSY }
                if (!released) try { Thread.sleep(100) } catch (_: InterruptedException) { interrupted = true }
            }
            proxyLease?.set(false)
            try { watcher?.join(1_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            stopReason.get()?.let { status = it }
            if (watcher?.isAlive == true || Thread.currentThread().isInterrupted || interrupted ||
                (cancelled.get() && stopReason.get() == null))
                status = Status.CANCELLED
            val after = try { protectedHashes(SystemClock.elapsedRealtime() + 4_000) } catch (_: Exception) { null }
            val unchanged = baseline != null && after != null && baseline == after
            output.put("userStoresUnchanged", if (baseline == null || after == null) JSONObject.NULL else if (unchanged) 1 else 0)
            if (!unchanged) status = if (after == null) Status.STORE_UNAVAILABLE else Status.USER_STORES_CHANGED
            if (SystemClock.elapsedRealtime() - began > 60_000) status = Status.DEADLINE
            if (status == Status.PASS_WIRE_AND_GT60 && (!retirementConfirmed || owner == null || !unchanged)) status = Status.NATIVE_FAILED
            output.put("status", status.name)
            if (interrupted) Thread.currentThread().interrupt()
            finish(if (status == Status.PASS_WIRE_AND_GT60) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("csqtt253_runtime", output.toString()) })
        }
    }

    private fun manual253(p: JSONObject) = Csqtt25Profile.isProfile(p) &&
        CsqttSwgVersions.needsNewRuntime(p) && CsqttGenerations.implementation(p) == IMPLEMENTATION &&
        !CsqttAutoDetection.negotiates(p) && p.optString("vk_hash_mode") == "manual" &&
        p.optString("hash_source", "manual") != "auto" && p.optString("vk_anon_path") != "auto_js" &&
        (p.optJSONArray("hashes")?.length() ?: 0) > 0

    private fun ensureIdle() {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        demand(!McrfVpnService.hasActiveSession() && !McrfVpnService.cleanupPending() && manager.allNetworks.none {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }, Status.ACTIVE_VPN)
        val plain = readXml(File(app.applicationInfo.dataDir, "shared_prefs/MainActivity.xml"))
        demand(!plain.containsKey(SafeModeStore.KEY) || plain[SafeModeStore.KEY] == false, Status.SAFE_MODE)
        demand(!plain.containsKey("wantedRunning") || plain["wantedRunning"] == false, Status.ACTIVE_VPN)
        demand(ProbeCoordinator.measurements == null && !ClientPrimaryRetirement.hasPending() &&
            staticField(ProbeCoordinator::class.java, "healthSchedule") == null &&
            staticField(ProbeCoordinator::class.java, "healthCancellation") == null, Status.PROBE_BUSY)
        val processes = app.getSystemService(ActivityManager::class.java).runningAppProcesses ?: throw Halt(Status.PROBE_BUSY)
        demand(processes.none { it.uid == android.os.Process.myUid() && it.pid != android.os.Process.myPid() }, Status.PROBE_BUSY)
        if (networkHandle != 0L) demand(DirectAccessCheck.physical(app)?.networkHandle == networkHandle, Status.NETWORK_CHANGED)
    }

    /** Same-UID children cannot escape this check by chdir or reparenting.
     * No cwd/cmdline/env/log reads, PID output or PID-based stop. An unknown
     * inventory fails closed; only a confirmed vanished /proc entry is skipped.
     */
    private fun noOtherOwnedUidProcess(): Boolean {
        val processes = try { File("/proc").listFiles() } catch (_: Exception) { null } ?: return false
        val self = android.os.Process.myPid().toString()
        val ownedUid = android.os.Process.myUid()
        var sawSelf = false
        for (directory in processes.filter { it.name.matches(Regex("[0-9]+")) }) {
            if (directory.name == self) { sawSelf = true; continue }
            val uid = try { Os.stat(directory.path).st_uid } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) continue
                return false
            } catch (_: Exception) {
                return false
            }
            if (uid == ownedUid) return false
        }
        return sawSelf
    }

    private fun checkWork() {
        stopReason.get()?.let { throw Halt(it) }
        demand(!cancelled.get() && !Thread.currentThread().isInterrupted, Status.CANCELLED)
        demand(SystemClock.elapsedRealtime() < workDeadline, Status.DEADLINE)
    }

    private fun observeOwnedConfig(owner: NativeCarrier, configured: Int) {
        val directory = NativeCarrier::class.java.getDeclaredField("directory").apply { isAccessible = true }.get(owner) as File
        demand(directory.canonicalFile.parentFile == File(app.applicationInfo.dataDir, "cache").canonicalFile &&
            directory.name.matches(Regex("mcrf-carrier-[0-9a-f-]{36}")), Status.CONFIGURATION_REJECTED)
        val p = JSONObject(String(bounded(File(directory, "config.json"), 65536), Charsets.UTF_8))
        demand(p.optString("csqtt_implementation") == IMPLEMENTATION && p.getInt("workers") == configured &&
            p.getBoolean("exact_workers"), Status.CONFIGURATION_REJECTED)
    }

    /** Domain ATYP=3 through authenticated owned SOCKS; no platform target DNS,
     * redirects, fallback, plaintext HTTP, trust-all certificates or body dump. */
    private fun proveHttps(owner: NativeCarrier) {
        val token = DiagnosticCancellation(minOf(5_000L, workDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        networkToken.set(token)
        try {
            Socket().use { socket ->
                val attached = token.attach(socket)
                try {
                    socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), owner.port), token.remainingMs())
                    DiagnosticsSocksConnect.connect(socket, HTTPS_HOST, owner.username, owner.password, token)
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, HTTPS_HOST, 443, true) as SSLSocket
                    tls.use {
                        val tlsAttached = token.attach(tls)
                        try {
                            tls.sslParameters = tls.sslParameters.apply {
                                endpointIdentificationAlgorithm = "HTTPS"
                                serverNames = listOf(SNIHostName(HTTPS_HOST))
                            }
                            tls.soTimeout = token.remainingMs(); tls.startHandshake(); checkWork(); token.check()
                            tls.outputStream.write("GET /generate_204 HTTP/1.1\r\nHost: $HTTPS_HOST\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            tls.outputStream.flush()
                            val bytes = ByteArray(8192)
                            try {
                                var size = 0
                                while (size < bytes.size) {
                                    checkWork(); tls.soTimeout = token.remainingMs()
                                    val value = tls.inputStream.read(); demand(value >= 0, Status.WIRE_FAILED)
                                    bytes[size++] = value.toByte()
                                    if (size >= 4 && bytes[size - 4] == 13.toByte() && bytes[size - 3] == 10.toByte() &&
                                        bytes[size - 2] == 13.toByte() && bytes[size - 1] == 10.toByte()) {
                                        val statusLine = String(bytes, 0, size, Charsets.US_ASCII).substringBefore("\r\n")
                                        demand(statusLine.matches(Regex("HTTP/1\\.[01] 204(?: [^\\r\\n]*)?")), Status.WIRE_FAILED)
                                        return
                                    }
                                }
                                throw Halt(Status.WIRE_FAILED)
                            } finally { bytes.fill(0) }
                        } finally { tlsAttached.close() }
                    }
                } finally { attached.close() }
            }
        } finally { token.cancel(); networkToken.compareAndSet(token, null) }
    }

    private fun existingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        demand(store.containsAlias("mcrf-profile-v1"), Status.STORE_UNAVAILABLE)
        return store.getKey("mcrf-profile-v1", null) as? SecretKey ?: throw Halt(Status.STORE_UNAVAILABLE)
    }

    private fun storeDirectory(): File {
        val root = File(app.applicationInfo.dataDir, "files"); stable(root)
        val pointer = File(root, "active-profile.txt"); stable(pointer)
        if (!pointer.exists()) return root
        val name = String(bounded(pointer, 128), Charsets.US_ASCII)
        demand(name.matches(Regex("mcrf-store-[0-9a-f-]{36}")), Status.STORE_UNAVAILABLE)
        return File(root, name).also { stable(it); demand(it.isDirectory && it.canonicalFile.parentFile == root.canonicalFile, Status.STORE_UNAVAILABLE) }
    }

    private fun encryptedJson(file: File, key: SecretKey): JSONObject {
        stable(file)
        if (!file.exists()) return JSONObject()
        val bytes = bounded(file, 8 * 1024 * 1024)
        try {
            demand(bytes.size >= 32 && java.nio.ByteBuffer.wrap(bytes, 0, 4).int == 0x4d435246, Status.STORE_UNAVAILABLE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(4, 16))) }
            val plain = cipher.doFinal(bytes, 16, bytes.size - 16)
            return try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
        } finally { bytes.fill(0) }
    }

    private fun readXml(file: File): Map<String, Any> {
        stable(file)
        if (!file.exists()) return emptyMap()
        val parser = android.util.Xml.newPullParser()
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false)
        parser.setInput(String(bounded(file, 4 * 1024 * 1024), Charsets.UTF_8).reader())
        val values = linkedMapOf<String, Any>()
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.DOCDECL -> throw Halt(Status.STORE_UNAVAILABLE)
                XmlPullParser.END_DOCUMENT -> return values
                XmlPullParser.START_TAG -> {
                    val name = parser.getAttributeValue(null, "name") ?: continue
                    demand(!values.containsKey(name), Status.STORE_UNAVAILABLE)
                    when (parser.name) {
                        "string" -> values[name] = parser.nextText()
                        "boolean" -> values[name] = when (parser.getAttributeValue(null, "value")) {
                            "true" -> true; "false" -> false; else -> throw Halt(Status.STORE_UNAVAILABLE)
                        }
                    }
                }
            }
        }
    }

    /** Exact expected side effects, NOT a blanket ignore of files/preferences.
     * Digest maps/paths never leave memory. Cache/native dirs aren't user stores.
     * No AndroidIDS exception: ALL shared preferences stay protected here. */
    private fun protectedHashes(deadline: Long): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var count = 0; var total = 0L
        val credential = File(app.applicationInfo.dataDir)
        val expectedEffects = listOf("client.log", "client.log.lock", "client.log.bak", "client.log.new",
            "vk-credential-backoff-v1", "vk-credential-backoff-v1.lock", "vk-credential-backoff-v1.bak", "vk-credential-backoff-v1.new")
            .map { File(credential, "files/$it").absoluteFile }.toSet()
        fun visit(label: String, file: File, depth: Int) {
            demand(SystemClock.elapsedRealtime() <= deadline && depth <= 6 && ++count <= 4096, Status.STORE_UNAVAILABLE)
            demand(!Files.isSymbolicLink(file.toPath()), Status.STORE_UNAVAILABLE)
            if (!file.exists()) return
            if (file.absoluteFile in expectedEffects) { demand(file.isFile, Status.STORE_UNAVAILABLE); return }
            if (file.isDirectory) {
                result["$label/"] = "DIRECTORY"
                val children = file.listFiles() ?: throw Halt(Status.STORE_UNAVAILABLE)
                demand(children.size <= 4096, Status.STORE_UNAVAILABLE)
                children.sortedBy { it.name }.forEach { visit("$label/${it.name}", it, depth + 1) }
            } else {
                demand(file.isFile && file.length() <= 128L * 1024 * 1024, Status.STORE_UNAVAILABLE)
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        demand(SystemClock.elapsedRealtime() <= deadline, Status.STORE_UNAVAILABLE)
                        val size = stream.read(buffer); if (size < 0) break
                        total += size; demand(size > 0 && total <= 128L * 1024 * 1024, Status.STORE_UNAVAILABLE)
                        digest.update(buffer, 0, size)
                    }
                }
                result[label] = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
        }
        for ((name, root) in listOf("credential" to credential,
            "device" to File(requireNotNull(app.applicationInfo.deviceProtectedDataDir))))
            for (child in listOf("files", "shared_prefs", "databases", "no_backup")) visit("$name/$child", File(root, child), 0)
        return result
    }

    private fun stable(file: File) {
        demand(!Files.isSymbolicLink(file.toPath()) && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists(), Status.STORE_UNAVAILABLE)
    }
    private fun bounded(file: File, maximum: Int): ByteArray {
        stable(file)
        return file.inputStream().use { stream ->
            val bytes = ByteArray(maximum + 1); var size = 0
            while (size < bytes.size) {
                val read = stream.read(bytes, size, bytes.size - size); if (read < 0) break
                demand(read > 0, Status.STORE_UNAVAILABLE); size += read
            }
            demand(size in 1..maximum, Status.STORE_UNAVAILABLE); bytes.copyOf(size)
        }
    }
    private fun staticField(type: Class<*>, name: String): Any? =
        type.getDeclaredField(name).apply { isAccessible = true }.get(null)
    private fun demand(value: Boolean, status: Status) { if (!value) throw Halt(status) }
    private companion object {
        const val PACKAGE = "org.mcrf.client"
        const val PROCESS = "org.mcrf.client:csqttRuntimeFixture"
        const val IMPLEMENTATION = "swg-panel-2.5.3"
        const val HTTPS_HOST = "www.gstatic.com"
    }
}
