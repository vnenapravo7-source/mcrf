package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only actual saved-server probes. No Activity, imports, preference writes,
 * engine installs, global selection changes or VPN stop. Never render Throwable.
 * Receipt uses an owned target-cache filename, outside all protected user stores. */
class DiagnosticsRuntimeInstrumentation : Instrumentation() {
    private enum class Stop {
        DEBUG_REQUIRED, ACTIVE_VPN, NO_NETWORK, PROBE_BUSY, SAFE_MODE,
        STORAGE_UNSTABLE, STORAGE_UNREADABLE, KEY_UNAVAILABLE, CATALOGUE_UNREADABLE,
        INSUFFICIENT_NORMAL, BS_ENGINE_UNAVAILABLE, STATE_CHANGED,
        SERVER_BUDGET, RUN_BUDGET, RUNTIME_ERROR
    }
    private class Halt(val status: Stop) : Exception()
    private val receiptLock = Any()
    private val rows = JSONArray()
    private val receipt = JSONObject().put("schema", 1).put("fixture", "diagnostics-runtime-v1")
        .put("status", "NOT_STARTED").put("results", rows).put("retiredSlots", JSONArray())
        .put("dnsScope", "CONTROL_DOH_THROUGH_PROXY_NOT_PROFILE_DNS_OR_APPLICATION")
    private var receiptFile: File? = null
    private var baseline: ProtectedState? = null
    private var finalGuard = false
    private val receiptWriteFailed = AtomicBoolean()

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val started = SystemClock.elapsedRealtime()
        val token = DiagnosticCancellation(180_000)
        val expired = AtomicBoolean()
        val timer = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "diagnostics-fixture-budget").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
        var code = Activity.RESULT_CANCELED
        try {
            // Instrumentation runs as target UID; test-package filesDir need not
            // be writable. Use a unique owned cache file, outside user stores.
            receiptFile = File(targetContext.cacheDir, "diagnostics-runtime-${java.util.UUID.randomUUID()}.json")
            if (requireNotNull(receiptFile).exists()) throw Halt(Stop.RUNTIME_ERROR)
            publish("PREFLIGHT")
            if (!BuildConfig.DEBUG) throw Halt(Stop.DEBUG_REQUIRED)
            ensureIdle()
            synchronized(ProfileStore.storageLock) {
                baseline = ProtectedState.capture(targetContext)
                if (requireNotNull(baseline).hasAtomicSidecars) throw Halt(Stop.STORAGE_UNSTABLE)
            }
            if (!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("mcrf-profile-v1"))
                throw Halt(Stop.KEY_UNAVAILABLE)
            val entries = try {
                synchronized(ProfileStore.storageLock) { ConnectionCatalogue(ProfileStore(targetContext, "connections.enc").read()).entries() }
            } catch (_: Exception) { throw Halt(Stop.CATALOGUE_UNREADABLE) }
            unchanged()
            val normal = entries.indices.filter { index -> entries[index].let {
                !it.whitelists && it.carrier == null && !it.needsEngine() && ProbePlan.supports(it)
            } }
            // Representative saved entries, preferring different protocols/hosts.
            // Exactly two normal and at most one BS; no searching for a green result.
            val selectedNormal = representative(normal, entries, 2)
            val bs = entries.indices.filter { entries[it].whitelists }
            val bsCandidates = bs.sortedBy { if (entries[it].carrier != null) 0 else 1 }.filter { index ->
                val entry = entries[index]
                entry.carrier != null || !entry.needsEngine() && ProbePlan.supports(entry)
            }
            // Package inspection is read-only; it launches no server probes.
            val bsModules = bsCandidates.associateWith { moduleLimitation(entries[it]) }
            val selectedBs = bsCandidates.firstOrNull { bsModules[it] == null } ?: bsCandidates.firstOrNull()
            synchronized(receiptLock) {
                receipt.put("catalogueCount", entries.size).put("eligibleNormal", normal.size).put("bsPresent", bs.isNotEmpty())
                receipt.put("selectedSlots", JSONArray((selectedNormal + listOfNotNull(selectedBs)).map { it + 1 }))
                receipt.put("budgetsMs", JSONObject().put("run", 180_000).put("server", 60_000).put("check", 6_000))
            }
            if (selectedNormal.size < 2) throw Halt(Stop.INSUFFICIENT_NORMAL)
            if (bs.isNotEmpty() && selectedBs == null) throw Halt(Stop.BS_ENGINE_UNAVAILABLE)
            val slots = selectedNormal + listOfNotNull(selectedBs)
            val moduleProblems = slots.associateWith { if (it in bsModules) bsModules[it] else moduleLimitation(entries[it]) }
            // Missing modules are an explicit unavailable result, never a download
            // or a request to change the user's selected primary.
            if (moduleProblems.values.any { it != null }) {
                synchronized(receiptLock) { receipt.put("action", "Required installed engine unavailable or unverified. No probes ran; no engines or settings were changed.") }
                for (index in slots) for (id in TARGETS) {
                    val limitation = moduleProblems.getValue(index)
                    append(index + 1, entries[index].whitelists, id,
                        ServerServiceMeasurement(index + 1, id, DiagnosticState.UNAVAILABLE,
                            limitation = limitation ?: ServerComparisonLimit.NONE),
                        if (limitation == null) "Not run: another selected profile requires an unavailable or unverified engine" else null)
                }
                publish("ENGINE_UNAVAILABLE")
            } else {
                val deadline = timer.schedule({ expired.set(true); token.cancel(); publish("RUN_BUDGET_WAITING_FOR_RETIREMENT") }, 180_000, TimeUnit.MILLISECONDS)
                val provider = DiagnosticsNativeComparisonProvider(targetContext, entries)
                try {
                    for (index in slots) {
                        if (expired.get()) throw Halt(Stop.RUN_BUDGET)
                        ensureIdle(); unchanged(); token.check()
                        val child = DiagnosticCancellation(60_000)
                        val link = token.attach(AutoCloseable { child.cancel() })
                        val serverExpired = AtomicBoolean()
                        val expiry = timer.schedule({ serverExpired.set(true); child.cancel() }, 60_000, TimeUnit.MILLISECONDS)
                        val completed = hashSetOf<String>()
                        try {
                            for (id in TARGETS) {
                                ensureIdle(); unchanged(); child.check()
                                publish("PROBING")
                                val measured = provider.measure(setOf(index + 1), requireNotNull(ServiceTestCatalog.find(id)), child).single()
                                append(index + 1, entries[index].whitelists, id, measured); completed.add(id)
                                ensureIdle(); unchanged()
                            }
                        } catch (failure: Exception) {
                            val status = when {
                                failure is Halt -> failure.status
                                expired.get() -> Stop.RUN_BUDGET
                                serverExpired.get() || failure is java.net.SocketTimeoutException -> Stop.SERVER_BUDGET
                                else -> Stop.RUNTIME_ERROR
                            }
                            for (id in TARGETS.filterNot { it in completed }) append(index + 1, entries[index].whitelists, id,
                                ServerServiceMeasurement(index + 1, id, DiagnosticState.UNAVAILABLE,
                                    limitation = if (status in setOf(Stop.SERVER_BUDGET, Stop.RUN_BUDGET)) ServerComparisonLimit.BUDGET else ServerComparisonLimit.PROVIDER_FAILURE))
                            synchronized(receiptLock) { receipt.put("lastStop", status.name) }
                            if (status != Stop.SERVER_BUDGET) throw Halt(status)
                        } finally {
                            // Cancellation asks the exact owned ingress to stop.
                            // Actual retirement precedes the next server and final
                            // state capture. Never claim cleanup on a timed-out wait.
                            child.cancel(); publish("RETIRING")
                            try { provider.finishServer() } finally { expiry.cancel(false); link.close() }
                            synchronized(receiptLock) { receipt.getJSONArray("retiredSlots").put(index + 1) }
                            unchanged()
                        }
                    }
                } finally { deadline.cancel(false); token.cancel(); provider.finishServer() }
                if (expired.get()) throw Halt(Stop.RUN_BUDGET)
                val normalOk = selectedNormal.count { successful(it + 1) }
                val bsOk = selectedBs == null || successful(selectedBs + 1)
                synchronized(receiptLock) { receipt.put("normalSuccessful", normalOk).put("bsSuccessful", bsOk) }
                publish(if (normalOk >= 2 && bsOk) "PROBES_PASSED" else "PROBES_FAILED")
            }
            unchanged(); finalGuard = true
            synchronized(receiptLock) { receipt.put("userBytesPreserved", true) }
            if (receipt.optString("status") == "PROBES_PASSED") { publish("PASS"); code = Activity.RESULT_OK }
        } catch (failure: Throwable) {
            val status = if (failure is Halt) failure.status else if (expired.get()) Stop.RUN_BUDGET else Stop.RUNTIME_ERROR
            synchronized(receiptLock) { receipt.put("stop", status.name).put("action", action(status)) }
            publish("ABORTED")
        } finally {
            token.cancel(); timer.shutdownNow()
            val timerStopped = try { timer.awaitTermination(2, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            if (!timerStopped) {
                code = Activity.RESULT_CANCELED
                synchronized(receiptLock) { receipt.put("action", "Budget watchdog has not retired; this run is not accepted.") }
                publish("WATCHDOG_PENDING")
            }
            // Always inspect final user state, including failed probes. No restore
            // or rollback: concurrent/user changes are detected, never overwritten.
            if (baseline != null) try {
                unchanged(); finalGuard = true
            } catch (_: Exception) {
                finalGuard = false; code = Activity.RESULT_CANCELED
                synchronized(receiptLock) { receipt.put("stop", Stop.STATE_CHANGED.name).put("action", action(Stop.STATE_CHANGED)) }
                publish("FAILED_USER_STATE")
            }
            synchronized(receiptLock) {
                receipt.put("userBytesPreserved", if (baseline == null) JSONObject.NULL else finalGuard)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            }
            publish(receipt.optString("status", "ABORTED"))
            if (receiptWriteFailed.get()) {
                code = Activity.RESULT_CANCELED
                synchronized(receiptLock) { receipt.put("status", "RECEIPT_WRITE_FAILED").put("action", "Sanitized receipt could not be saved. This run is not accepted.") }
            }
            finish(code, Bundle().apply {
                // Whitelisted generated text/filename only. Never stringify errors.
                putString("stream", "Diagnostics runtime: ${receipt.optString("status")}\n" +
                    "User bytes preserved: ${if (baseline == null) "unmeasured" else finalGuard}\n" +
                    "Receipt (app cache): ${receiptFile?.name ?: "unavailable"}\n" +
                    receipt.optString("action", ""))
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureIdle() {
        val app = targetContext
        val manager = app.getSystemService(ConnectivityManager::class.java)
        if (McrfVpnService.hasActiveSession() || manager.allNetworks.any {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) throw Halt(Stop.ACTIVE_VPN)
        if (DirectAccessCheck.physical(app) == null) throw Halt(Stop.NO_NETWORK)
        if (ProbeCoordinator.measurements != null || ClientPrimaryRetirement.hasPending()) throw Halt(Stop.PROBE_BUSY)
        if (SafeModeStore(app).enabled()) throw Halt(Stop.SAFE_MODE)
    }

    private fun unchanged() {
        val before = requireNotNull(baseline)
        val after = synchronized(ProfileStore.storageLock) { ProtectedState.capture(targetContext) }
        val equal = before.sameBytes(after)
        synchronized(receiptLock) { receipt.put("guard", JSONObject().put("beforeFiles", before.bytes.size)
            .put("afterFiles", after.bytes.size).put("equal", equal)) }
        if (!equal) throw Halt(Stop.STATE_CHANGED)
    }

    private fun moduleLimitation(entry: ServerImport.Server): ServerComparisonLimit? = try {
        if (entry.carrier == null) EngineUpdatePrimary.installation(targetContext, "sing-box")
        else NativeCarrier.installation(targetContext, EngineModules.carrierKind(requireNotNull(entry.carrier)))
        null
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        if (entry.carrier == null) ServerComparisonLimit.SINGBOX_MISSING else ServerComparisonLimit.UNSUPPORTED_PROVIDER
    } catch (_: Exception) {
        if (entry.carrier == null) ServerComparisonLimit.SINGBOX_UNVERIFIED else ServerComparisonLimit.UNSUPPORTED_PROVIDER
    }

    private fun append(slot: Int, bs: Boolean, id: String, measured: ServerServiceMeasurement, reason: String? = null) {
        val result = measured.diagnostic
        val row = JSONObject().put("slot", slot).put("section", if (bs) "BS" else "NORMAL")
            .put("target", id).put("check", requireNotNull(ServiceTestCatalog.find(id)).check.name)
            .put("state", measured.state.name).put("limitation", measured.limitation.name)
            .put("reason", reason ?: result?.let(DiagnosticsPresentation::resultReason) ?: DiagnosticsPresentation.comparisonReason(measured.limitation))
            .put("issue", result?.issue?.name).put("elapsedMs", result?.elapsedMs)
            .put("dnsScope", result?.dnsScope?.name).put("dnsFailure", result?.dnsFailure?.name)
            .put("proxyFailure", result?.proxyFailure?.name)
        row.put("evidence", JSONArray(result?.evidence.orEmpty().map { evidence ->
            JSONObject().put("stage", evidence.stage.name).put("state", evidence.state.name).put("issue", evidence.issue.name)
                .put("elapsedMs", evidence.elapsedMs).put("httpStatus", evidence.httpStatus)
                .put("ipv4Answers", evidence.ipv4Answers).put("ipv6Answers", evidence.ipv6Answers).put("dnsRcode", evidence.dnsRcode)
        }))
        synchronized(receiptLock) { rows.put(row) }
        publish("MEASURED")
    }

    private fun successful(slot: Int): Boolean = synchronized(receiptLock) {
        TARGETS.all { id -> (0 until rows.length()).map { rows.getJSONObject(it) }.any {
            it.getInt("slot") == slot && it.getString("target") == id && it.getString("state") == DiagnosticState.AVAILABLE.name &&
                (id != "discord-voice" || it.optString("dnsScope") == DiagnosticDnsScope.CONTROL_DOH_THROUGH_PROXY.name)
        } }
    }

    private fun publish(status: String) = synchronized(receiptLock) {
        receipt.put("status", status)
        val target = receiptFile ?: return@synchronized
        // Only sanitized receipt data, never configuration, server identity,
        // native output, exception text, user-state bytes or their paths.
        val atomic = AtomicFile(target)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite(); output.write(receipt.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output)
        } catch (_: Exception) { receiptWriteFailed.set(true); atomic.failWrite(output) }
    }

    private fun action(stop: Stop) = when (stop) {
        Stop.ACTIVE_VPN -> "Active VPN detected. Main must coordinate stopping only MCRF if authorized; do not stop another VPN. Then rerun."
        Stop.STATE_CHANGED -> "User state changed during the fixture. Preserve evidence; do not restore or overwrite user data."
        Stop.INSUFFICIENT_NORMAL -> "Need two saved normal profiles supported by the isolated sing-box ingress. Nothing was imported."
        Stop.BS_ENGINE_UNAVAILABLE -> "Saved BS entries have no supported ingress. No engine was installed or downloaded."
        Stop.STORAGE_UNSTABLE -> "Storage has pending atomic sidecars. Wait for normal writes/recovery to finish before rerunning."
        else -> "Inspect the typed receipt and resolve the reported precondition before rerunning. No user settings were changed by this fixture."
    }

    /** Raw protected bytes stay private in RAM. Even names/paths/hashes are not
     * exported. SharedPreferences includes credential/device-protected storage. */
    private class ProtectedState(val bytes: Map<String, ByteArray>, val preferences: Map<String, *>) {
        val hasAtomicSidecars get() = bytes.keys.any { it.endsWith(".bak") || it.endsWith(".new") }
        fun sameBytes(other: ProtectedState) = preferences == other.preferences && bytes.keys == other.bytes.keys &&
            bytes.all { (key, value) -> value.contentEquals(requireNotNull(other.bytes[key])) }
        companion object {
            fun capture(app: Context): ProtectedState {
                val bytes = linkedMapOf<String, ByteArray>()
                var total = 0L
                fun file(label: String, target: File) {
                    if (Files.isSymbolicLink(target.toPath())) throw Halt(Stop.STORAGE_UNREADABLE)
                    if (!target.exists()) return
                    if (!target.isFile || target.length() > 16 * 1024 * 1024) throw Halt(Stop.STORAGE_UNREADABLE)
                    val value = target.inputStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            total += count
                            if (total > 64 * 1024 * 1024) throw Halt(Stop.STORAGE_UNREADABLE)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    bytes[label] = value
                }
                fun directory(label: String, root: File, all: Boolean) {
                    if (!root.exists()) return
                    if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) throw Halt(Stop.STORAGE_UNREADABLE)
                    bytes["$label/"] = byteArrayOf()
                    val children = root.listFiles() ?: throw Halt(Stop.STORAGE_UNREADABLE)
                    for (child in children.sortedBy { it.name }) {
                        if (all || child.name.contains(".enc") || child.name.startsWith("active-profile.txt")) file("$label/${child.name}", child)
                        else if (child.name.startsWith("mcrf-store-")) directory("$label/${child.name}", child, false)
                    }
                }
                try {
                    directory("credential-preferences", File(app.dataDir, "shared_prefs"), true)
                    val device = app.createDeviceProtectedStorageContext()
                    directory("device-preferences", File(device.dataDir, "shared_prefs"), true)
                    directory("stores", app.filesDir, false)
                    // AtomicFile.openRead / SharedPreferences may restore .bak.
                    // Refuse BEFORE those APIs are called, preserving exact bytes.
                    if (bytes.keys.any { it.endsWith(".bak") || it.endsWith(".new") }) throw Halt(Stop.STORAGE_UNSTABLE)
                    // Clone StringSet values; never keep a live mutable set alias.
                    val preferences = app.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).all.mapValues { (_, value) ->
                        if (value is Set<*>) value.toSet() else value
                    }
                    return ProtectedState(bytes, preferences)
                } catch (failure: Halt) { throw failure }
                catch (_: Exception) { throw Halt(Stop.STORAGE_UNREADABLE) }
            }
        }
    }

    companion object {
        private val TARGETS = listOf("github-site", "discord-voice")
        private fun representative(indices: List<Int>, entries: List<ServerImport.Server>, count: Int): List<Int> {
            val result = mutableListOf<Int>()
            for (index in indices) if (result.none { entries[it].protocol == entries[index].protocol || entries[it].host == entries[index].host }) {
                result.add(index); if (result.size == count) return result
            }
            for (index in indices) if (index !in result) { result.add(index); if (result.size == count) break }
            return result
        }
    }
}
