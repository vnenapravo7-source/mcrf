package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject

/** Disposable emulator only. The host peer is reachable through the emulator's loopback alias. */
class VayDnsOwnedServiceInstrumentation : Instrumentation() {
    private var parameters = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        parameters = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val context = targetContext
        val result = Bundle()
        var outcome = Activity.RESULT_CANCELED
        var phase = "scope"
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        var savedPreferences: Map<String, *>? = null
        var savedProfiles: Map<String, String>? = null
        val current = McrfVpnService::class.java.getDeclaredField("currentService").apply { isAccessible = true }
        val core = McrfVpnService::class.java.getDeclaredField("core").apply { isAccessible = true }
        fun service() = current.get(null) as? McrfVpnService
        try {
            check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(service() == null && !McrfVpnService.hasActiveSession())
            check(VpnService.prepare(context) == null) { "owned emulator VPN consent missing" }
            check(BuildConfig.NATIVE_ROUTER_PINS.isNotEmpty()) { "bundled router missing" }
            for (kind in listOf("sing-box", "steer")) {
                val installed = try {
                    context.packageManager.getApplicationInfo(EngineModules.packageName(kind), 0)
                    true
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    false
                }
                check(!installed) { "primary module installed in owned emulator: $kind" }
            }
            val resolverPort = parameters.getString("resolverPort")?.toIntOrNull()
                ?.takeIf { it in 1024..65535 } ?: error("invalid fixture resolver port")
            val key = requireNotNull(parameters.getString("publicKey"))
            require(key.matches(Regex("[0-9a-f]{64}")))
            val kind = parameters.getString("transport", VayDnsProfile.KIND)
            require(kind in setOf(VayDnsProfile.KIND, "dnstt"))
            val raw = JSONObject().put("type", kind).put("name", "$kind owned fixture")
                .put("domain", "t.example.test").put("key", key).put("udp", "10.0.2.2:$resolverPort")
            if (kind == VayDnsProfile.KIND) raw.put("username", "remote").put("password", "owned-password")
            val entry = if (kind == VayDnsProfile.KIND) VayDnsProfile.fromJson(raw.toString(), raw)
                else CarrierProfile.fromJson(raw.toString(), raw)
            check(BsNativeContract.carrierRouter(context, requireNotNull(entry.carrier), true))

            savedPreferences = preferences.all.toMap()
            savedProfiles = ProfileStore.snapshot(context)
            ProfileStore(context, "preferences.enc").write("{}")
            val catalogue = Connections(context).apply { load(); add(raw.toString()); load() }
            val stored = catalogue.entries().single { it.id == entry.id }
            val source = ServerImport.build(listOf(stored), stored.id, false, 60, 50, "Cloudflare")
            ProfileStore(context).write(source.toString())
            check(preferences.edit().remove(ClientPrimarySelection.KEY).putBoolean("automatic", false)
                .putBoolean("catalogueProfile", true).putString("selected", stored.id)
                .putBoolean(BsDetectionMonitor.KEY, false).putBoolean("wantedRunning", false).commit())

            phase = "ordinary START without a primary"
            context.startForegroundService(Intent(context, McrfVpnService::class.java).setAction(McrfVpnService.START)
                .putExtra("tun", true).putExtra("applyRoutes", false).putExtra("pendingWhitelists", true))
            val deadline = SystemClock.elapsedRealtime() + 25_000
            var worker: ClientPrimaryWorker? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                worker = service()?.let { core.get(it) as? ClientPrimaryWorker }
                if (worker?.state == "RUNNING" && McrfVpnService.status.startsWith("VPN подключён")) break
                Thread.sleep(50)
            }
            check(worker?.choiceId == BsNativeContract.CHOICE && worker?.state == "RUNNING" &&
                McrfVpnService.status.startsWith("VPN подключён")) { "autonomous worker did not reach ready state" }

            phase = "TUN through $kind to the owned peer"
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val vpn = connectivity.allNetworks.single { network ->
                connectivity.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.ownerUid == Process.myUid()
                } == true
            }
            val destination = InetAddress.getByAddress(byteArrayOf(198.toByte(), 51, 100, 7))
            val payload = "OWNED".toByteArray(Charsets.US_ASCII)
            Socket().use { socket ->
                vpn.bindSocket(socket)
                socket.soTimeout = 10_000
                socket.connect(InetSocketAddress(destination, 443), 10_000)
                socket.getOutputStream().write(payload)
                val echoed = ByteArray(payload.size)
                DataInputStream(socket.getInputStream()).readFully(echoed)
                check(echoed.contentEquals(payload)) { "owned peer echo mismatch" }
            }
            check(ClientPrimarySelection.selected(context) == null)
            result.putString("scope", "ordinary VPN START, $kind physical UDP resolver, TUN TCP echo through owned peer, no installed or selected primary")
            outcome = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("phase", phase)
            result.putString("failure", error.javaClass.simpleName + ": " + error.message)
        } finally {
            if (savedProfiles != null && service() != null) {
                context.startService(Intent(context, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                val until = SystemClock.elapsedRealtime() + 15_000
                while (service() != null && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
                if (service() != null) {
                    outcome = Activity.RESULT_CANCELED
                    result.putString("stop", "service did not retire")
                }
            }
            savedProfiles?.let { ProfileStore.replaceAll(context, it) }
            savedPreferences?.let { saved ->
                val edit = preferences.edit().clear()
                saved.forEach { (name, value) ->
                    when (value) {
                        is String -> edit.putString(name, value)
                        is Boolean -> edit.putBoolean(name, value)
                        is Int -> edit.putInt(name, value)
                        is Long -> edit.putLong(name, value)
                        is Float -> edit.putFloat(name, value)
                        is Set<*> -> edit.putStringSet(name, value.map { it as String }.toSet())
                        else -> error("unexpected preference type")
                    }
                }
                check(edit.commit())
            }
        }
        finish(outcome, result)
    }
}
