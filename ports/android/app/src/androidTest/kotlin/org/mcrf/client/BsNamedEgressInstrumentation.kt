package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Platform
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

/** Exercises the signed private worker with an Android TUN and owned SOCKS exits. */
open class BsNamedEgressInstrumentation : Instrumentation() {
    protected open val physicalFixture = false
    private var arguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var phase = "preflight"
        var code = Activity.RESULT_CANCELED
        try {
            check(BuildConfig.DEBUG)
            if (physicalFixture) {
                check(Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.first() == "arm64-v8a" &&
                    Build.HARDWARE !in setOf("ranchu", "goldfish") && !Build.FINGERPRINT.startsWith("generic"))
            } else check(Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(!McrfVpnService.hasActiveSession() && VpnService.prepare(targetContext) == null)
            val packages = targetContext.packageManager
            val embedded = BuildConfig.NATIVE_ROUTER_PINS.isNotEmpty()
            val proxyOnly = arguments.getString("proxyOnly") == "true"
            val pairPin = if (physicalFixture) requireNotNull(arguments.getString("pairSha256")) else null
            val physicalPackages = pairPin?.let { ClientP1OwnerFixtureScope.packages(targetContext, it) }
            val helperUid = physicalPackages?.let { packages.getApplicationInfo(it[1], 0).uid }
            val nativeSha = if (physicalFixture) {
                check(embedded && !proxyOnly)
                val elf = File(targetContext.applicationInfo.nativeLibraryDir, "libmcrf_router.so")
                BsEngineContract.sha(elf.inputStream()).also { check(it == arguments.getString("fixtureSha256")) }
            } else null
            if (proxyOnly) check(embedded && BsNativeContract.proxyHint())
            if (!embedded) {
                check(packages.getApplicationInfo(BsEngineContract.PACKAGE, 0).flags and ApplicationInfo.FLAG_TEST_ONLY != 0)
            }
            val excludedEngines = listOf("org.mcrf.engines.singbox", "org.mcrf.engines.steer") +
                listOfNotNull(BsEngineContract.PACKAGE.takeIf { embedded })
            for (primary in if (physicalFixture) emptyList() else excludedEngines) {
                try {
                    packages.getApplicationInfo(primary, 0)
                    error("Primary engine present: $primary")
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
            val manager = targetContext.getSystemService(ConnectivityManager::class.java)
            val saved = if (physicalFixture) {
                ClientPrimaryVpnDeviceChecks.requireIdle(targetContext, manager)
                ClientPrimaryVpnDeviceChecks.Saved.capture(targetContext)
            } else null
            check(manager.allNetworks.none {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            })
            val physical = checkNotNull(manager.activeNetwork)
            val openFluxExit = arguments.getString("openfluxExitPort")?.toInt()
            val openFluxEcho = arguments.getString("openfluxEchoPort")?.toInt()
            val openFluxAddress = arguments.getString("openfluxEchoAddress")?.let(RouteRules::literal)
            require(listOf(openFluxExit, openFluxEcho, openFluxAddress).count { it != null } in setOf(0, 3))
            require(listOfNotNull(openFluxExit, openFluxEcho).all { it in 1024..65535 })
            require(openFluxAddress == null || openFluxAddress is Inet4Address && openFluxAddress.isSiteLocalAddress)
            require(!proxyOnly || openFluxExit == null)
            require(!physicalFixture || openFluxExit == null)
            val facade = McrfVpnService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                isAccessible = true
            }.invoke(facade, targetContext)
            val loopback = InetAddress.getByName("127.0.0.1")
            ServerSocket(0, 1, loopback).use { first ->
                ServerSocket(0, 1, loopback).use { second ->
                    phase = "named route capture"
                    val firstId = "a".repeat(64)
                    val secondId = "b".repeat(64)
                    val egresses = listOf(
                        BsNativeRoutingSnapshot.Egress(firstId, first.localPort, "owned-username-a", "owned-password-a"),
                        BsNativeRoutingSnapshot.Egress(secondId, second.localPort, "owned-username-b", "owned-password-b"),
                    )
                    val document = JSONObject(
                        """{
                          "route":{"final":"chosen","rules":[
                            {"protocol":"dns","action":"hijack-dns"},
                            {"ip_cidr":["192.0.2.42/32"],"action":"route","outbound":"${egresses[1].tag}"}]},
                          "outbounds":[{"type":"socks","tag":"chosen"},{"type":"socks","tag":"${egresses[1].tag}"}],
                          "dns":{"servers":[{"type":"hosts","tag":"owned","predefined":{"owned.invalid":"198.51.100.9"}}],"final":"owned"},
                          "inbounds":[{"type":"tun","tag":"tun","address":["10.89.0.2/32"],"mtu":1500,
                            "route_address":["192.0.2.41/32","192.0.2.42/32","198.19.255.53/32"],
                            "include_package":["${targetContext.packageName}"]}]
                        }""",
                    )
                    if (physicalPackages != null) {
                        document.getJSONArray("inbounds").getJSONObject(0)
                            .put("address", JSONArray(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")))
                            .put("include_package", JSONArray(physicalPackages))
                            .getJSONArray("route_address")
                            .put("${ClientP1StrictOwnerFixture.IPV4}/32").put("${ClientP1StrictOwnerFixture.IPV6}/128")
                    }
                    val proxyPort = if (proxyOnly) ServerSocket(0, 1, loopback).use { it.localPort } else null
                    if (proxyPort != null) {
                        document.put("inbounds", JSONArray().put(JSONObject().put("type", "mixed")
                            .put("tag", "owned-proxy").put("listen", "127.0.0.1").put("listen_port", proxyPort)
                            .put("users", JSONArray().put(JSONObject().put("username", proxyUser).put("password", proxyPassword)))))
                    }
                    val snapshot = BsNativeRoutingSnapshot.capture(
                        document.apply {
                            // A carrier that forgets to bind its provider socket
                            // would send the emulator gateway into this TUN.
                            if (openFluxExit != null) getJSONArray("inbounds").getJSONObject(0)
                                .getJSONArray("route_address").put("10.0.2.2/32")
                        },
                        "chosen", emptyMap(), egresses = egresses, mainEgressId = firstId,
                    )
                    val payload = JSONObject()
                        .put("schema", 1)
                        .put("bundle", snapshot.bundle())
                        .put("profile", JSONObject.NULL)
                        .put("physical", physical.networkHandle)
                        .put("underlay", JSONObject().put("kind", "wifi").put("expensive", false)
                            .put("constrained", false).put("ssid", "").put("bssid", ""))
                        .put("metered", false)
                        .put("tunnelTag", "chosen")
                    val pin = BsNativeContract.routerPin(targetContext)
                    phase = "private worker and TUN"
                    val approved = AtomicInteger()
                    val denied = AtomicInteger()
                    val workerHost = object : ClientPrimaryHostPlatform() {
                        override fun vpnOwnerIsolationRequired() = physicalFixture
                        override fun admitsConnectionOwner(owner: Platform.ConnectionOwner): Boolean {
                            if (!physicalFixture) return super.admitsConnectionOwner(owner)
                            if (owner.uid == helperUid && owner.packageNames.toSet() == physicalPackages?.drop(1)?.toSet())
                                denied.incrementAndGet()
                            return (owner.uid == Process.myUid() && owner.packageNames.toSet() == setOf(targetContext.packageName))
                                .also { if (it) approved.incrementAndGet() }
                        }
                        fun attach() {
                            attachBaseContext(targetContext)
                            vpnMode = !proxyOnly
                        }
                    }.apply { attach(); onCreate() }
                    val stopping = AtomicBoolean()
                    var tun: ParcelFileDescriptor? = null
                    val pool = Executors.newFixedThreadPool(4)
                    try {
                        val platform = workerHost.forRun(45, stopping::get, { plan ->
                            check(!proxyOnly)
                            val builder = facade.Builder().setSession("MCRF owned named egress").setMtu(plan.mtu)
                            (plan.routes.inet4Address + plan.routes.inet6Address).forEach {
                                val (address, prefix) = it.split('/')
                                builder.addAddress(address, prefix.toInt())
                            }
                            (plan.routes.inet4Range + plan.routes.inet6Range).forEach {
                                val (address, prefix) = it.split('/')
                                builder.addRoute(address, prefix.toInt())
                            }
                            plan.dnsServers.forEach(builder::addDnsServer)
                            plan.includePackage.forEach(builder::addAllowedApplication)
                            plan.excludePackage.forEach(builder::addDisallowedApplication)
                            checkNotNull(builder.establish()).also { tun = it }
                        }) { _, _ -> }
                        ClientPrimaryWorker.connectBs(targetContext, 45, platform, pin).use { worker ->
                            worker.validateBs(payload)
                            check(tun == null)
                            worker.startBs()
                            check((tun != null) == !proxyOnly && worker.state == "RUNNING")
                            val ownedNetwork = if (physicalFixture) awaitOwnedVpn(manager) else null
                            if (physicalFixture) check(ClientPrimaryVpnDeviceChecks.tunInterface(Process.myPid(),
                                requireNotNull(tun).fd.toString()) == manager.getLinkProperties(requireNotNull(ownedNetwork))?.interfaceName)
                            phase = if (proxyOnly) "parallel TCP through the local proxy" else "parallel TCP through the Android TUN"
                            val exitA = pool.submit { replyFromEgress(first, 41, 'a') }
                            val exitB = pool.submit { replyFromEgress(second, 42, 'b') }
                            val flowA = pool.submit { verifyFlow(41, 'a', proxyPort, ownedNetwork) }
                            val flowB = pool.submit { verifyFlow(42, 'b', proxyPort, ownedNetwork) }
                            listOf(exitA, exitB, flowA, flowB).forEach { it.get(8, TimeUnit.SECONDS) }
                            phase = "native DNS"
                            if (proxyPort == null) verifyTunDns(ownedNetwork) else verifyProxyDns(proxyPort)
                            if (physicalFixture) check(approved.get() >= 3) { "PHYSICAL_ROOT_ROUTER_OWNER_BROKER_EVIDENCE" }
                            if (physicalPackages != null) {
                                phase = "production entry rejects the actual other UID before egress"
                                BsNativePhysicalDirectOwnerInstrumentation.verifyExcluded(targetContext, requireNotNull(ownedNetwork),
                                    physicalPackages[1], requireNotNull(helperUid),
                                    listOf(ClientP1StrictOwnerFixture.IPV4, ClientP1StrictOwnerFixture.IPV6))
                                check(denied.get() >= 2) { "PHYSICAL_ROOT_ROUTER_ACTUAL_DENIED_OWNER_EVIDENCE" }
                                for (origin in listOf(first, second)) {
                                    origin.soTimeout = 250
                                    try {
                                        origin.accept().use { error("PHYSICAL_ROOT_ROUTER_DENIED_UID_REACHED_EGRESS") }
                                    } catch (_: SocketTimeoutException) {}
                                }
                            }
                            worker.stats()
                            if (openFluxExit != null) {
                                phase = "signed OpenFlux bound outside the active TUN"
                                val profile = JSONObject()
                                    .put("type", "openflux").put("negotiate", true)
                                    .put("secret", "owned fixture encryption")
                                    .put("context", "owned named egress fixture")
                                    .put("transports", JSONArray().put(JSONObject()
                                        .put("type", "direct").put("dial", "10.0.2.2:$openFluxExit")))
                                check(BsNativeContract.carrierRouter(targetContext, profile, true))
                                val deadline = android.os.SystemClock.elapsedRealtime() + 12000
                                NativeCarrier.startForBsRouter(targetContext, profile, physical, BooleanSupplier {
                                    android.os.SystemClock.elapsedRealtime() > deadline
                                }).use { verifyOpenFlux(it, checkNotNull(openFluxAddress), checkNotNull(openFluxEcho)) }
                                result.putString("openflux", "signed carrier reached the owned exit outside the active TUN")
                            }
                            phase = "stop private worker"
                        }
                        if (proxyPort != null) ServerSocket(proxyPort, 1, loopback).use {}
                    } finally {
                        stopping.set(true)
                        pool.shutdownNow()
                        workerHost.onDestroy()
                        tun?.close()
                    }
                }
            }
            if (physicalFixture) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                fun hasVpn() = manager.allNetworks.any {
                    manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                while (hasVpn() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                check(!hasVpn() && !ClientPrimaryRetirement.hasPending())
                check(ClientPrimaryVpnDeviceChecks.Saved.capture(targetContext) == saved)
                result.putString("physicalRouterEntry", "ARM64_PRODUCTION_ROUTER_ENTRY_OWNER_NAMED_EGRESSES_ACCEPTED")
                result.putString("fixtureSha256", nativeSha)
                result.putString("pairSha256", pairPin)
                result.putString("generation", "2.5.3")
                result.putBoolean("ownedCleanupAndProfileParity", true)
                result.putBoolean("providerTraffic", false)
            }
            result.putString("scope", if (proxyOnly) "authenticated local proxy without Android TUN, two TCP exits, native DNS and stop"
                else "actual Android TUN, two authenticated TCP exits, native DNS and stop, no primary engine")
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("phase", phase)
            result.putString("failure", error.stackTraceToString().take(3000))
        }
        finish(code, result)
    }

    private fun replyFromEgress(listener: ServerSocket, host: Int, exit: Char) {
        listener.soTimeout = 6000
        listener.accept().use { peer ->
            peer.soTimeout = 6000
            val input = DataInputStream(peer.getInputStream())
            val output = DataOutputStream(peer.getOutputStream())
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 2)
            output.write(byteArrayOf(5, 2))
            check(input.readUnsignedByte() == 1)
            fun readField() = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
            check(readField() == "owned-username-$exit" && readField() == "owned-password-$exit")
            output.write(byteArrayOf(1, 0))
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
            check(input.readUnsignedByte() == 0 && input.readUnsignedByte() == 1)
            check(input.readInt() == (0xc0000200L + host).toInt() && input.readUnsignedShort() == 23456)
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            check(input.readUnsignedByte() == host)
            output.writeByte(exit.code)
        }
    }

    private fun verifyFlow(host: Int, exit: Char, proxyPort: Int?, network: Network?) {
        val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, host.toByte()))
        val connection = if (proxyPort == null) Socket().apply {
            network?.bindSocket(this)
            connect(InetSocketAddress(address, 23456), 6000)
        } else proxyConnection(proxyPort, proxyUser, proxyPassword, address, 23456)
        connection.use { socket ->
            socket.soTimeout = 6000
            socket.getOutputStream().write(host)
            check(socket.getInputStream().read() == exit.code)
        }
    }

    private fun verifyTunDns(network: Network?) {
        val query = PacketDnsWire.query("owned.invalid", 1, 2111)
        DatagramSocket().use { socket ->
            network?.bindSocket(socket)
            socket.soTimeout = 6000
            socket.connect(InetAddress.getByName("198.19.255.53"), 53)
            socket.send(DatagramPacket(query, query.size))
            val reply = DatagramPacket(ByteArray(1024), 1024)
            socket.receive(reply)
            check(PacketDnsWire.addresses(query, reply.data.copyOf(reply.length)) == listOf(InetAddress.getByName("198.51.100.9")))
        }
    }

    private fun awaitOwnedVpn(manager: ConnectivityManager): Network {
        val deadline = SystemClock.elapsedRealtime() + 5000
        do {
            val owned = manager.allNetworks.singleOrNull { network ->
                manager.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.ownerUid == Process.myUid()
                } == true
            }
            if (owned != null) return owned
            Thread.sleep(20)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("PHYSICAL_ROOT_ROUTER_NO_OWNED_VPN")
    }

    private fun verifyProxyDns(port: Int) {
        val query = PacketDnsWire.query("owned.invalid", 1, 2111)
        proxyConnection(port, proxyUser, proxyPassword, InetAddress.getByName("198.19.255.53"), 53).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            output.writeShort(query.size)
            output.write(query)
            val input = DataInputStream(socket.getInputStream())
            val reply = ByteArray(input.readUnsignedShort()).also(input::readFully)
            check(PacketDnsWire.addresses(query, reply) == listOf(InetAddress.getByName("198.51.100.9")))
        }
    }

    private fun verifyOpenFlux(carrier: NativeCarrier, echoAddress: InetAddress, echoPort: Int) {
        proxyConnection(carrier.port, carrier.username, carrier.password, echoAddress, echoPort).use { peer ->
            peer.getOutputStream().write(71)
            check(peer.getInputStream().read() == 71)
        }
    }

    private fun proxyConnection(port: Int, user: String, password: String, address: InetAddress, targetPort: Int): Socket {
        val peer = Socket("127.0.0.1", port)
        try {
            peer.soTimeout = 5000
            val input = DataInputStream(peer.getInputStream())
            val output = DataOutputStream(peer.getOutputStream())
            output.write(byteArrayOf(5, 1, 2))
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2)
            output.writeByte(1)
            for (credential in listOf(user, password)) {
                val bytes = credential.toByteArray(Charsets.US_ASCII)
                output.writeByte(bytes.size)
                output.write(bytes)
            }
            check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
            output.write(byteArrayOf(5, 1, 0, 1))
            output.write(address.address)
            output.writeShort(targetPort)
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0 && input.readUnsignedByte() == 0)
            val addressBytes = when (input.readUnsignedByte()) {
                1 -> 4
                4 -> 16
                else -> error("unexpected SOCKS address")
            }
            input.readFully(ByteArray(addressBytes + 2))
            return peer
        } catch (error: Throwable) {
            peer.close()
            throw error
        }
    }

    private companion object {
        const val proxyUser = "owned-local-proxy-user"
        const val proxyPassword = "owned-local-proxy-password"
    }
}

class BsNativePhysicalRouterEntryInstrumentation : BsNamedEgressInstrumentation() {
    override val physicalFixture = true
}
