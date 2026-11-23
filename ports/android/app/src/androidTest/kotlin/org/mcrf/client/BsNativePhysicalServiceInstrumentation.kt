package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Ordinary Main START/STOP and signed native worker, using a private synthetic input. */
class BsNativePhysicalServiceInstrumentation : Instrumentation() {
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
        val nonce = UUID.randomUUID().toString()
        val root = File(app.cacheDir, "mcrf-test-$nonce")
        val file = File(root, "service-fixture.json")
        var code = Activity.RESULT_CANCELED
        var phase = "preflight"
        var created = false
        var started = false
        var ownedService: McrfVpnService? = null
        var epoch: Long? = null
        var worker: ClientPrimaryWorker? = null
        var workerPid: Int? = null
        var tun: ParcelFileDescriptor? = null
        var saved: ClientPrimaryVpnDeviceChecks.Saved? = null
        var quic: ClientQuicOwnerChecks? = null

        fun owned() = service()?.takeIf {
            (fixtureField.get(it) as? BsNativePhysicalServiceFixture)?.nonce == nonce
        }
        fun currentVpn(): Network? = manager.allNetworks.singleOrNull { network ->
            manager.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.ownerUid == Process.myUid()
            } == true
        }
        fun assertCurrent() {
            check(owned() === ownedService && epochField.getLong(ownedService) == epoch &&
                workerField.get(ownedService) === worker && worker?.state == "RUNNING" &&
                tun?.fileDescriptor?.valid() == true) { "OWNED_BS_SERVICE_GENERATION_REPLACED" }
        }

        try {
            check(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.first() == "arm64-v8a" &&
                Build.HARDWARE !in setOf("goldfish", "ranchu") && !Build.FINGERPRINT.startsWith("generic"))
            check(service() == null && !McrfVpnService.hasActiveSession() && !ClientPrimaryRetirement.hasPending())
            ClientPrimaryVpnDeviceChecks.requireIdle(app, manager)
            check(VpnService.prepare(app) == null) { "NORMAL_ANDROID_VPN_CONSENT_REQUIRED" }
            val generation = arguments.getString("generation")
            val pin = arguments.getString("moduleSha256")
            require(generation in setOf("2.5.1", "2.5.3") && pin?.matches(Regex("[0-9a-f]{64}")) == true)
            check(BsNativeContract.pin(app) == pin)
            saved = ClientPrimaryVpnDeviceChecks.Saved.capture(app)
            val quicPair = arguments.getString("quicPairSha256")
            if (quicPair != null) {
                phase = "native-quic-origin"
                require(quicPair.matches(Regex("[0-9a-f]{64}")))
                quic = ClientQuicOwnerChecks.open(app, quicPair)
            }
            val loopback = InetAddress.getByName("127.0.0.1")
            val port = ServerSocket(0, 1, loopback).use { it.localPort }
            check(root.mkdir())
            created = true
            Os.chmod(root.path, 0x1c0)
            check(file.createNewFile())
            Os.chmod(file.path, 0x180)
            val fixture = JSONObject()
                .put("nonce", nonce).put("createdElapsed", SystemClock.elapsedRealtime())
                .put("generation", generation).put("moduleSha256", pin)
            quic?.let { fixture.put("quicOriginPort", it.port).put("quicPairSha256", quicPair) }
            file.writeText(JSONObject().put("bsNativePhysical", fixture).toString(), Charsets.UTF_8)
            phase = "main-start"
            app.startForegroundService(Intent(app, McrfVpnService::class.java).setAction(McrfVpnService.START)
                .putExtra("tun", true).putExtra("applyRoutes", false).putExtra("port", port)
                .putExtra("test_fixture", file.path))
            started = true
            await(35000) {
                owned()?.let { candidate ->
                    if (ownedService == null) {
                        ownedService = candidate
                        epoch = epochField.getLong(candidate)
                    }
                    check(ownedService === candidate && epochField.getLong(candidate) == epoch)
                    worker = workerField.get(candidate) as? ClientPrimaryWorker ?: worker
                    worker?.remotePid?.takeIf { it > 0 }?.let { workerPid = it }
                    tun = tunField.get(candidate) as? ParcelFileDescriptor
                }
                check(!McrfVpnService.status.startsWith("Ошибка")) { "OWNED_BS_SERVICE_START_FAILED" }
                worker?.state == "RUNNING" && tun != null && McrfVpnService.status.startsWith("VPN подключён")
            }
            val liveWorker = requireNotNull(worker)
            val descriptor = requireNotNull(tun)
            val pid = requireNotNull(workerPid)
            check(liveWorker.choiceId == BsNativeContract.CHOICE && pid != Process.myPid() && File("/proc/$pid").isDirectory)
            check(ClientPrimaryVpnDeviceChecks.strictScopeInstalled(requireNotNull(ownedService), descriptor))
            var network: Network? = null
            await(6000) { currentVpn().also { network = it } != null }
            val vpn = requireNotNull(network)
            val links = requireNotNull(manager.getLinkProperties(vpn))
            val iface = requireNotNull(links.interfaceName)
            check(ClientPrimaryVpnDeviceChecks.tunInterface(Process.myPid(), descriptor.fd.toString()) == iface)
            phase = "real-tun-dns"
            check(links.dnsServers.isNotEmpty())
            for (dns in links.dnsServers) {
                assertCurrent()
                val query = PacketDnsWire.query("owned.invalid", 1, 2331)
                DatagramSocket(null).use { socket ->
                    vpn.bindSocket(socket)
                    socket.bind(InetSocketAddress(0))
                    socket.connect(dns, 53)
                    socket.soTimeout = 5000
                    socket.send(DatagramPacket(query, query.size))
                    val reply = DatagramPacket(ByteArray(2048), 2048)
                    socket.receive(reply)
                    check(PacketDnsWire.addresses(query, reply.data.copyOfRange(reply.offset, reply.offset + reply.length)) ==
                        listOf(InetAddress.getByName("198.51.100.9")))
                    check(manager.getConnectionOwnerUid(17, InetSocketAddress(socket.localAddress, socket.localPort),
                        InetSocketAddress(dns, 53)) == Process.myUid())
                }
            }
            phase = "native-socks-auth-loopback"
            ServerSocket(0, 1, loopback).use { listener ->
                listener.soTimeout = 6000
                val payload = ("SSH-2.0-owned-" + nonce + "\r\n").toByteArray(Charsets.US_ASCII)
                val echo = FutureTask {
                    listener.accept().use { peer ->
                        peer.soTimeout = 5000
                        check(peer.inetAddress.isLoopbackAddress)
                        val bytes = ByteArray(payload.size)
                        val input = DataInputStream(peer.getInputStream())
                        input.readFully(bytes)
                        check(bytes.contentEquals(payload) && input.read() == -1)
                        peer.getOutputStream().write(bytes)
                        peer.shutdownOutput()
                    }
                }
                val thread = Thread(echo, "bs-owned-physical-echo").apply { isDaemon = true; start() }
                try {
                    Socket(loopback, port).use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        output.write(byteArrayOf(5, 1, 2)); output.flush()
                        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2)
                        val auth = (fixtureField.get(ownedService) as BsNativePhysicalServiceFixture).auth()
                        val user = auth.getString("username").toByteArray(Charsets.US_ASCII)
                        val password = auth.getString("password").toByteArray(Charsets.US_ASCII)
                        output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(password.size.toByte()) + password)
                        output.flush()
                        check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
                        output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1))
                        output.writeShort(listener.localPort)
                        output.flush()
                        val ack = ByteArray(10)
                        input.readFully(ack)
                        check(ack[0].toInt() == 5 && ack[1].toInt() == 0)
                        output.write(payload); output.flush(); socket.shutdownOutput()
                        val answer = ByteArray(payload.size)
                        input.readFully(answer)
                        check(answer.contentEquals(payload) && input.read() == -1)
                    }
                    echo.get(6000, TimeUnit.MILLISECONDS)
                } finally {
                    listener.close()
                    thread.join(6500)
                    check(!thread.isAlive)
                }
            }
            quic?.let { ownedQuic ->
                phase = "native-direct-udp-quic"
                val checks = mutableListOf<String>()
                ownedQuic.run(vpn, ::assertCurrent, Consumer { checks.add(it) })
                check(checks.size == 4) { "OWNED_BS_QUIC_CHECKS" }
                result.putString("nativeDirectQuic", "ARM64_NATIVE_ROUTED_DIRECT_UDP_QUIC_OWNER_ACCEPTED")
                result.putString("quicPairSha256", quicPair)
            }
            assertCurrent()
            result.putString("physicalService", "ARM64_MAIN_START_NATIVE_WORKER_TUN_OWNER_ACCEPTED")
            result.putString("generation", generation)
            result.putString("moduleSha256", pin)
            result.putBoolean("providerTraffic", false)
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("phase", phase)
            result.putString("failure", failureCode(error, "OWNED_BS_FIXTURE_FAILED"))
        } finally {
            try {
                if (started) {
                    val remaining = owned()
                    if (remaining != null) {
                        check(ownedService == null || ownedService === remaining && epochField.getLong(remaining) == epoch)
                        app.startService(Intent(app, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                    }
                    await(25000) {
                        service() == null && currentVpn() == null && tun?.fileDescriptor?.valid() != true &&
                            workerPid?.let { !File("/proc/$it").exists() } != false && !ClientPrimaryRetirement.hasPending()
                    }
                }
                if (created) {
                    check(root.canonicalFile.parentFile == app.cacheDir.canonicalFile &&
                        !java.nio.file.Files.isSymbolicLink(root.toPath()) && !java.nio.file.Files.isSymbolicLink(file.toPath()))
                    check(!file.exists() || file.delete())
                    check(root.delete())
                }
                saved?.let { check(ClientPrimaryVpnDeviceChecks.Saved.capture(app) == it) { "OWNED_PROFILE_PARITY" } }
                if (code == Activity.RESULT_OK) result.putBoolean("ownedCleanupAndProfileParity", true)
            } catch (error: Throwable) {
                code = Activity.RESULT_CANCELED
                result.putString("cleanupFailure", failureCode(error, "OWNED_BS_CLEANUP_FAILED"))
            }
            try {
                quic?.close()
            } catch (error: Throwable) {
                code = Activity.RESULT_CANCELED
                result.remove("ownedCleanupAndProfileParity")
                result.putString("cleanupFailure", "OWNED_BS_QUIC_PROCESS_CLEANUP")
            }
            finish(code, result)
        }
    }

    private companion object {
        fun failureCode(error: Throwable, fallback: String): String = error.javaClass.simpleName + ": " +
            (error.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{3,79}")) } ?: fallback)
        fun field(name: String) = McrfVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }
        val serviceField = field("currentService")
        val fixtureField = field("fixtureBsNativePhysical")
        val epochField = field("epoch")
        val workerField = field("core")
        val tunField = ClientPrimaryHostPlatform::class.java.getDeclaredField("tunnel").apply { isAccessible = true }
        fun service() = serviceField.get(null) as? McrfVpnService

        fun await(timeout: Long, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { "OWNED_BS_SERVICE_TIMEOUT" }
                Thread.sleep(20)
            }
        }
    }
}
