package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Original Direct TUN dispatcher and Android owner broker, with captured synthetic egress. */
class BsNativePhysicalDirectOwnerInstrumentation : Instrumentation() {
    private var arguments = Bundle()

    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        arguments = args ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val app = targetContext.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val failed = AtomicBoolean()
        var code = Activity.RESULT_CANCELED
        var phase = "preflight"
        var saved: ClientPrimaryVpnDeviceChecks.Saved? = null
        var tun: ParcelFileDescriptor? = null
        var session: CsqttDirectTunSession? = null
        var ownedFiles: BsNativeOwnedRuntimeFiles? = null
        val families = listOf("172.19.0.1" to ClientP1StrictOwnerFixture.IPV4,
            "fdfe:dcba:9876::1" to ClientP1StrictOwnerFixture.IPV6)
        try {
            check(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.first() == "arm64-v8a" &&
                Build.HARDWARE !in setOf("goldfish", "ranchu") && !Build.FINGERPRINT.startsWith("generic"))
            ClientPrimaryVpnDeviceChecks.requireIdle(app, manager)
            check(VpnService.prepare(app) == null) { "NORMAL_ANDROID_VPN_CONSENT_REQUIRED" }
            val generation = requireNotNull(arguments.getString("generation"))
            val pin = requireNotNull(arguments.getString("fixtureSha256"))
            val pairPin = requireNotNull(arguments.getString("pairSha256"))
            val installed = ClientP1OwnerFixtureScope.packages(app, pairPin)
            val helper = installed[1]
            val pm = app.packageManager
            val helperUid = pm.getApplicationInfo(helper, 0).uid
            val scope = VpnOwnerIsolation.capture(true, listOf(app.packageName), installed.drop(1),
                { name -> pm.getApplicationInfo(name, 0).uid },
                { uid -> pm.getPackagesForUid(uid)?.toList().orEmpty() })
            check(scope.admits(Process.myUid(), listOf(app.packageName)) && !scope.admits(helperUid, installed.drop(1)))
            val meta = JSONObject(context.assets.open("bs-owned-native-udp-fixture.json").bufferedReader().use { it.readText() })
            val binary = File(context.applicationInfo.nativeLibraryDir, "libmcrf_owned_native_udp_fixture.so")
            check(meta.getBoolean("directOwnerCapture") && meta.getString("abi") == "arm64-v8a" &&
                generation in setOf("2.5.1", "2.5.3") && meta.getString("generation") == generation &&
                meta.getString("sha256") == pin && meta.getLong("bytes") == binary.length() &&
                BsEngineContract.sha(binary.inputStream()) == pin)
            saved = ClientPrimaryVpnDeviceChecks.Saved.capture(app)
            val facade = McrfVpnService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                isAccessible = true
            }.invoke(facade, app)
            val profile = JSONObject().put("type", "csqtt").put("csqtt_generation", "2.5")
                .put("csqtt_implementation", "swg-panel-$generation").put("server", "192.0.2.1").put("server_port", 9)
                .put("password", "owned-fixture-only").put("workers", 1).put("hashes", JSONArray().put("owned-fixture-only"))
            phase = "direct-owner-fd-adoption"
            val fileOwner = BsNativeOwnedRuntimeFiles.instrumentation(app, context, profile, binary, pin)
            ownedFiles = fileOwner
            session = CsqttDirectTunSession.start(fileOwner, profile, BsDirectTunContract.Installation(binary, generation),
                42, { false }, {
                    // Deliberately deliver the pinned helper into the fixture TUN.
                    // The actual Direct owner broker policy admits only Main.
                    val builder = facade.Builder().setSession("MCRF Owned Direct Owner Capture").setMtu(1400).setBlocking(false)
                        .addAddress(families[0].first, 30).addAddress(families[1].first, 126)
                        .addRoute(families[0].second, 32).addRoute(families[1].second, 128)
                    installed.forEach(builder::addAllowedApplication)
                    requireNotNull(builder.establish()).also { tun = it }
                }, { failed.set(true) }, directOwnerScope = scope)
            val owned = requireNotNull(session)
            val interfaceName = ClientPrimaryVpnDeviceChecks.tunInterface(Process.myPid(), requireNotNull(tun).fd.toString())
            fun current(network: Network): Boolean = owned.isAlive() && !failed.get() &&
                manager.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.ownerUid == Process.myUid()
                } == true && manager.getLinkProperties(network)?.interfaceName == interfaceName
            var vpn: Network? = null
            await(6000) {
                vpn = manager.allNetworks.singleOrNull(::current)
                vpn != null
            }
            val network = requireNotNull(vpn)
            check(current(network))

            for ((index, family) in families.withIndex()) {
                phase = if (index == 0) "selected-udp-v4-network-bind" else "selected-udp-v6-network-bind"
                val destination = target(family.second)
                bindOwnedNetwork(network) { current(network) }.use { socket ->
                    phase = if (index == 0) "selected-udp-v4-exchange" else "selected-udp-v6-exchange"
                    socket.bind(InetSocketAddress(InetAddress.getByName(family.first), 0))
                    socket.connect(destination)
                    socket.soTimeout = 4000
                    val bytes = "MCRF-OWNED-DIRECT-V${if (index == 0) 4 else 6}".toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(bytes, bytes.size))
                    val response = DatagramPacket(ByteArray(128), 128)
                    socket.receive(response)
                    check(response.socketAddress == destination && response.data.copyOf(response.length).contentEquals(bytes))
                    check(manager.getConnectionOwnerUid(17, InetSocketAddress(socket.localAddress, socket.localPort),
                        destination) == Process.myUid())
                }
            }
            phase = "excluded-received-no-egress-v4-v6"
            verifyExcluded(app, network, helper, helperUid, families.map { it.second })

            phase = "source-closed-no-owner"
            val destination = target(families[0].second)
            val source = DatagramSocket(null).use { socket ->
                network.bindSocket(socket)
                socket.bind(InetSocketAddress(InetAddress.getByName(families[0].first), 0))
                socket.connect(destination)
                val payload = "MCRF-OWNED-DIRECT-MISSING".toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(payload, payload.size))
                InetSocketAddress(socket.localAddress, socket.localPort)
            }
            await(400) { manager.getConnectionOwnerUid(17, source, destination) < 0 }
            Thread.sleep(1000)
            check(owned.isAlive() && !failed.get())
            phase = "native-exit-and-egress-invariants"
            val child = CsqttDirectTunSession::class.java.getDeclaredField("child").apply { isAccessible = true }
                .get(owned) as java.lang.Process
            owned.close()
            session = null
            check(!child.isAlive && child.exitValue() == 0) { "DIRECT_OWNER_CAPTURE_ASSERTIONS_FAILED" }
            check(ClientP1OwnerFixtureScope.packages(app, pairPin) == installed)
            result.putString("physicalDirectOwner", "ARM64_ORIGINAL_DIRECT_FD_OWNER_CAPTURE_ACCEPTED")
            result.putString("generation", generation)
            result.putString("fixtureSha256", pin)
            result.putString("pairSha256", pairPin)
            result.putBoolean("providerTraffic", false)
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("phase", phase)
            result.putString("failure", error.javaClass.simpleName + ": " + error.message)
        } finally {
            try {
                try { session?.close() } finally { try { tun?.close() } finally { ownedFiles?.close() } }
                if (tun != null) await(5000) {
                    manager.allNetworks.none { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
                }
                saved?.let { check(ClientPrimaryVpnDeviceChecks.Saved.capture(app) == it) { "DIRECT_OWNER_PROFILE_PARITY" } }
                if (code == Activity.RESULT_OK) result.putBoolean("ownedCleanupAndProfileParity", true)
            } catch (error: Throwable) {
                code = Activity.RESULT_CANCELED
                result.putString("cleanupFailure", error.javaClass.simpleName + ": " + error.message)
            }
            finish(code, result)
        }
    }

    companion object {
    internal fun verifyExcluded(app: Context, network: Network, helper: String, uid: Int, hosts: List<String>) {
        val thread = HandlerThread("bs-owned-direct-probe-replies").apply { start() }
        val connected = CompletableFuture<Messenger>()
        var nonce = ""
        var result = CompletableFuture<Int>()
        var stopped = CompletableFuture<Unit>()
        val lock = Any()
        val reply = Messenger(Handler(thread.looper) { message ->
            synchronized(lock) {
                if (message.sendingUid == uid && message.data.getString("nonce") == nonce) {
                    when (message.what) {
                        3, 5, 7 -> result.complete(message.what)
                        4 -> stopped.complete(Unit)
                    }
                }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(Messenger(binder)) }
            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(lock) {
                    val failure = IllegalStateException("DIRECT_OWNER_PROBE_DIED")
                    result.completeExceptionally(failure)
                    stopped.completeExceptionally(failure)
                }
            }
        }
        var bound = false
        try {
            bound = app.bindService(Intent().setComponent(ComponentName(helper, "$helper.OwnerProbeService")),
                connection, Context.BIND_AUTO_CREATE)
            check(bound)
            val remote = connected.get(5, TimeUnit.SECONDS)
            for (host in hosts) {
                val request = synchronized(lock) {
                    nonce = UUID.randomUUID().toString().replace("-", "")
                    result = CompletableFuture()
                    stopped = CompletableFuture()
                    Bundle().apply { putString("nonce", nonce) }
                }
                remote.send(Message.obtain(null, 6).apply {
                    replyTo = reply
                    data = Bundle(request).apply {
                        putString("host", host)
                        putInt("port", ClientP1StrictOwnerFixture.PORT)
                        putInt("protocol", 17)
                        putLong("network", network.networkHandle)
                        putBoolean("connected", true)
                    }
                })
                try {
                    // 7 means Android never delivered the excluded test packet.
                    // Rust's own ingress bitmap independently requires it.
                    check(result.get(5, TimeUnit.SECONDS) == 5) { "DIRECT_OWNER_NEGATIVE_DELIVERY_OR_ECHO" }
                } finally {
                    remote.send(Message.obtain(null, 2).apply { replyTo = reply; data = request })
                    stopped.get(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            try { if (bound) app.unbindService(connection) }
            finally {
                thread.quitSafely()
                thread.join(3000)
                check(!thread.isAlive)
            }
        }
    }

        private fun target(host: String) = InetSocketAddress(InetAddress.getByName(host), ClientP1StrictOwnerFixture.PORT)
        private fun bindOwnedNetwork(network: Network, current: () -> Boolean): DatagramSocket {
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (true) {
                check(current()) { "DIRECT_OWNER_VPN_REPLACED" }
                val socket = DatagramSocket(null)
                try {
                    network.bindSocket(socket)
                    return socket
                } catch (error: Throwable) {
                    socket.close()
                    if (error !is SocketException || SystemClock.elapsedRealtime() >= deadline) throw error
                    Thread.sleep(50)
                }
            }
        }
        private fun await(timeout: Long, condition: () -> Boolean) {
            val stop = SystemClock.elapsedRealtime() + timeout
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < stop) { "DIRECT_OWNER_WAIT_TIMEOUT" }
                Thread.sleep(10)
            }
        }
    }
}
