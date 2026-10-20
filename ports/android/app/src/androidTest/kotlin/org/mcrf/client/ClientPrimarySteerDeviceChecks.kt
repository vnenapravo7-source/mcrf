package org.mcrf.client

import android.content.Context
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.steerprep.SteerMcrfSnapshotAdapter
import java.io.File
import java.util.function.Consumer

/** Installed Steer acceptance through the real private worker, independent of prototype proofs.
 * Preflight only: no START, TUN, socket, DNS lookup, saved profile or preference access.
 * The synthetic original URI is passed verbatim; localhost is parsed, never resolved/dialled.
 */
object ClientPrimarySteerDeviceChecks {
    private const val PROFILE = "vless://00000000-0000-4000-8000-000000000001@localhost:18990?encryption=none&security=none&type=tcp#Steer-preflight-only"
    private const val MUTATION_OWNER = "steer_preflight"

    @JvmStatic fun run(context: Context, passed: Consumer<String>) {
        fun check(ok: Boolean, label: String) {
            if (!ok) throw IllegalStateException(label)
            passed.accept(label)
        }
        require(Looper.myLooper() != Looper.getMainLooper()) { "Steer preflight requires a background thread" }
        val app = context.applicationContext
        // Strings only: no engine implementation type may be resolved by the parent loader.
        for (name in listOf("org.steer.core.SteerCore", "org.mcrf.engines.steer.SteerEngine",
            "go.Seq", "io.nekohasekai.libbox.Libbox")) {
            var absent = false
            try { Class.forName(name, false, app.classLoader) }
            catch (_: ClassNotFoundException) { absent = true }
            check(absent, "Steer preflight parent does not define $name")
        }

        val snapshot = compatibleSnapshot()
        val originalSnapshot = snapshot.toString()
        val projected = SteerMcrfSnapshotAdapter.convert(originalSnapshot)
        check(projected.supported && projected.unsupported.isEmpty() && projected.settingsJson != null,
            "Steer adapter accepts a complete synthetic compatible snapshot")
        val settings = requireNotNull(projected.settingsJson)
        check(sameJson(minimalSettings(), JSONObject(settings)) && snapshot.toString() == originalSnapshot,
            "Steer projection preserves exactly the owner's six minimal settings without changing its input")
        val incompatible = JSONObject(originalSnapshot).apply { getJSONObject("tun").put("ipv6", true) }
        val refused = SteerMcrfSnapshotAdapter.convert(incompatible.toString())
        check(!refused.supported && refused.settingsJson == null && refused.unsupported.any {
            it.code == "TUN_UNSUPPORTED" && it.field == "tun.ipv6"
        }, "Steer adapter rejects incompatible TUN settings before worker use")

        val generation = System.nanoTime().coerceAtLeast(1L)
        val session = ClientPrimaryWorker.connect(app, "steer", generation, null)
        val pid = session.remotePid
        var failure: Throwable? = null
        try {
            check(pid > 0 && pid != Process.myPid() && session.choiceId == "steer" &&
                session.generation == generation && session.state == "READY" &&
                Os.stat("/proc/$pid").st_uid == Process.myUid() &&
                File("/proc/$pid/cmdline").readText().substringBefore('\u0000') == app.packageName + ":primary",
                "Installed Steer preflight runs in an authenticated separate primary worker")
            val validation = session.validate(PROFILE, settings, Engine.Mode.VPN)
            check(validation.supported && validation.mode == Engine.Mode.VPN &&
                validation.tunAcquisition == Engine.TunAcquisition.PROVIDED &&
                validation.code.isEmpty() && validation.field.isEmpty(),
                "Installed Steer JNI accepts the original synthetic VLESS URI in provided-TUN VPN mode (parse only)")
            val expectedPlan = JSONObject("""{
                "schema":1,"mtu":1500,"addresses":["10.89.0.2/32"],"routes":["0.0.0.0/0"],
                "dns":["10.89.0.1"],"excludedApps":[],"ipv6":"block_for_captured_apps",
                "hostMustBeInsideVpn":true,"requiresVpnNetworkHandle":true
            }""")
            check(sameJson(expectedPlan, JSONObject(session.tunPlan(PROFILE, settings, Engine.Mode.VPN))),
                "Installed Steer returns the exact owner TUN plan including host and VPN-network requirements")

            for ((profile, label) in listOf(
                "" to "empty profile",
                PROFILE.replace("00000000-0000-4000-8000-000000000001@", "@") to "empty VLESS identity",
                PROFILE.replace("type=tcp", "type=kcp") to "unsupported VLESS transport"
            )) check(!session.validate(profile, settings, Engine.Mode.VPN).supported,
                "Installed Steer rejects $label")
            val unknown = JSONObject(settings).put("advancedDns", JSONObject().put("enabled", false)).toString()
            val badDns = JSONObject(settings).apply { getJSONObject("globalDns").put("protocol", "DoH3") }.toString()
            val forcedApps = JSONObject(settings).put("vpnApps", JSONArray().put("org.example.steerpreflight")).toString()
            val perRuleDns = JSONObject(settings).put("rules", JSONArray().put(JSONObject()
                .put("id", "fixture").put("name", "Synthetic rule").put("text", "DOMAIN,example.invalid")
                .put("mode", "addresses").put("target", "proxy").put("enabled", true)
                .put("dns", JSONObject(settings).getJSONObject("globalDns")))).toString()
            for ((invalid, label) in listOf(
                "{" to "malformed settings JSON", "{}" to "missing DNS settings",
                unknown to "unprojected settings even when disabled", badDns to "unsupported DNS protocol",
                forcedApps to "forced application routing", perRuleDns to "per-rule DNS"
            )) check(!session.validate(PROFILE, invalid, Engine.Mode.VPN).supported,
                "Installed Steer rejects $label")
            check(!session.validate(PROFILE, settings, Engine.Mode.PROXY).supported,
                "Installed Steer refuses proxy mode instead of silently using VPN mode")
            check(planRejected(session, "", settings) && planRejected(session, PROFILE, unknown),
                "Worker PLAN independently refuses invalid profiles and settings")
            check(session.validate(PROFILE, settings, Engine.Mode.VPN).supported && session.state == "READY" &&
                sameJson(expectedPlan, JSONObject(session.tunPlan(PROFILE, settings, Engine.Mode.VPN))),
                "Rejected inputs leave the same Steer worker usable for preflight without starting it")

            // beginMutation alone also sees the parent's usage refcount. Inspect the worker's
            // own kernel lock to independently prove the other process holds this exact gate.
            check(workerHoldsUsageLock(app, pid), "Steer worker PID holds its own shared kernel mutation lease")
            val unexpected = EngineUpdateGate.beginMutation(app, MUTATION_OWNER, "flux", "steer")
            if (unexpected != null) clearOwnMutation(app, unexpected)
            check(unexpected == null, "Live Steer preflight blocks engine mutation across processes")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                session.close()
                check(awaitPidGone(pid) && session.state == "STOPPED",
                    "Steer preflight close is followed by actual worker PID disappearance")
            } catch (cleanup: Throwable) {
                val primary = failure
                if (primary == null) throw cleanup
                primary.addSuppressed(cleanup)
            }
        }
        // A flux-kind gate probe consumes no installer request code and performs no update.
        val released = EngineUpdateGate.beginMutation(app, MUTATION_OWNER, "flux", "steer")
            ?: throw IllegalStateException("Steer worker death did not release both processes' mutation leases")
        // Clear before reporting success: even a throwing Consumer must not orphan the marker.
        clearOwnMutation(app, released)
        passed.accept("Steer worker death releases both processes' mutation leases")
        passed.accept("Steer preflight gate probe cleared; no TUN, traffic or reachability claim")
    }

    private fun minimalSettings() = JSONObject("""{
        "version":1,"defaultTarget":"proxy",
        "globalDns":{"provider":"Cloudflare","protocol":"DoH","custom":""},
        "excludedApps":[],"vpnApps":[],"rules":[]
    }""")

    /** Complete adapter schema, using synthetic values from the owner's compatible example.
     * No stores/defaults are read: real app defaults are deliberately incompatible with Steer.
     */
    private fun compatibleSnapshot() = JSONObject().put("schema", 1).put("mode", "vpn")
        .put("acceptSteerIpv4DnsModel", true).put("additionalRequiredFeatures", JSONArray())
        .put("routing", minimalSettings())
        .put("tun", JSONObject("""{
            "mtu":1500,"ipv6":false,"address4":"10.89.0.2/32","address6":"fdfe:dcba:9876::1/126",
            "bypassPrivate":false,"privateRanges":"10.0.0.0/8","bypassDirectIps":false,
            "mixedEnabled":false,"randomPort":false,"mixedPort":10808,"httpProxy":false,
            "httpBypass":"","shareLan":false,"lanPort":10809,"dnsPort":0,"customInbounds":""
        }"""))
        .put("coreTuning", JSONObject("""{
            "certificateStore":"system","insecure":false,"ntpEnabled":false,
            "ntpServer":"time.cloudflare.com","ntpPort":123,"ntpInterval":"30m",
            "clashEnabled":false,"clashPort":9090,"clashSecret":"","apiEnabled":false,"apiPort":9091,"apiSecret":"",
            "muxDefault":false,"muxProtocol":"smux","muxConcurrency":8,"muxPadding":false,
            "fragmentDefault":false,"fragmentDelay":"500ms","fingerprint":"","idleTimeout":"","keepAlive":"",
            "streamWindow":"","connectionWindow":"","maxStreams":0,"initialPacketSize":0,"disablePmtu":false
        }"""))

    private fun sameJson(expected: Any, actual: Any): Boolean = when (expected) {
        is JSONObject -> actual is JSONObject && expected.keys().asSequence().toSet() == actual.keys().asSequence().toSet() &&
            expected.keys().asSequence().all { sameJson(expected.get(it), actual.get(it)) }
        is JSONArray -> actual is JSONArray && expected.length() == actual.length() &&
            (0 until expected.length()).all { sameJson(expected.get(it), actual.get(it)) }
        else -> expected == actual
    }

    private fun planRejected(session: ClientPrimaryWorker, profile: String, settings: String): Boolean {
        try { session.tunPlan(profile, settings, Engine.Mode.VPN) }
        catch (error: IllegalStateException) {
            // Timeout, IPC failure, worker death and load failure are never acceptance evidence.
            if (error.message == "PRIMARY_WORKER_COMMAND_FAILED") return true
            throw error
        }
        return false
    }

    private fun workerHoldsUsageLock(context: Context, pid: Int): Boolean {
        val gate = File(context.noBackupFilesDir.canonicalFile, "engine-update-gate/usage.lock").canonicalFile
        val inode = Os.stat(gate.path).st_ino.toString()
        val entries = File("/proc/$pid/fdinfo").listFiles()
            ?: throw IllegalStateException("Steer worker kernel lease evidence unavailable")
        for (entry in entries) {
            val target = try { Os.readlink("/proc/$pid/fd/${entry.name}") }
            catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) continue
                throw error
            }
            if (target != gate.path) continue
            val held = entry.readLines().any { line ->
                val fields = line.trim().split(Regex("\\s+"))
                fields.size == 9 && fields[0] == "lock:" && fields[3] == "ADVISORY" && fields[4] == "READ" &&
                    (fields[2] == "POSIX" && fields[5] == pid.toString() || fields[2] == "OFDLCK" && fields[5] == "-1") &&
                    fields[6].substringAfterLast(':') == inode && fields[7] == "0" && fields[8] == "EOF"
            }
            if (held) return true
        }
        return false
    }

    private fun awaitPidGone(pid: Int): Boolean {
        require(pid > 0 && pid != Process.myPid())
        val deadline = SystemClock.elapsedRealtime() + 3000L
        while (true) {
            try { Os.stat("/proc/$pid") }
            catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) return true
                throw error // Permission/I/O errors cannot stand in for process death.
            }
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(20)
        }
    }

    private fun clearOwnMutation(context: Context, lease: EngineUpdateGate.Mutation) {
        try {
            check(EngineUpdateGate.complete(context, lease.pending.token) {
                it.token == lease.pending.token && it.owner == MUTATION_OWNER && it.action == "flux" && it.kind == "steer"
            }) { "Steer preflight could not clear its own gate probe" }
        } finally { lease.close() }
    }
}
