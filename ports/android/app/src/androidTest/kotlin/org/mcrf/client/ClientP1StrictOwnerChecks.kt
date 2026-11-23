package org.mcrf.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Real TUN sockets and loopback origins with separate positive and denied UID controls. */
internal class ClientP1StrictOwnerChecks(
    private val context: Context,
    private val pin: String,
    private val scope: String,
    private val fixtureNonce: String
) : AutoCloseable {
    private class Family(
        val destination: InetAddress,
        val source: InetAddress,
        val loopback: InetAddress,
        val tcp: ServerSocket,
        val udp: DatagramSocket
    ) : AutoCloseable {
        override fun close() {
            try { tcp.close() } finally { udp.close() }
        }
    }

    private class Session(val nonce: String) {
        val result = CompletableFuture<Pair<Int, Bundle>>()
        val retired = CompletableFuture<Unit>()
    }

    private val families = run {
        val v4 = openFamily(ClientP1StrictOwnerFixture.IPV4, "172.19.0.1", "127.0.0.1")
        try {
            listOf(v4, openFamily(ClientP1StrictOwnerFixture.IPV6, "fdfe:dcba:9876::1", "::1"))
        } catch (failure: Throwable) {
            v4.close()
            throw failure
        }
    }

    private val worker = Executors.newSingleThreadExecutor()

    fun json(): JSONObject = JSONObject().put("p1StrictOwner", JSONObject()
        .put("nonce", fixtureNonce).put("createdElapsed", SystemClock.elapsedRealtime())
        .put("pairSha256", pin).put("scope", scope)
        .put("ports", JSONArray(families.flatMap { listOf(it.tcp.localPort, it.udp.localPort) })))

    fun run(network: Network, passed: Consumer<String>) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val links = requireNotNull(cm.getLinkProperties(network))
        for (family in families) {
            check(links.linkAddresses.any { it.address == family.source }) { "P1_STRICT_OWNER_TUN_SOURCE" }
            check(links.routes.any { it.type == android.net.RouteInfo.RTN_UNICAST && it.destination.contains(family.destination) }) {
                "P1_STRICT_OWNER_TUN_ROUTE"
            }
        }
        val packages = ClientP1OwnerFixtureScope.packages(context, pin)
        val helper = packages[1]
        val helperUid = context.packageManager.getApplicationInfo(helper, 0).uid
        val thread = HandlerThread("p1-strict-owner-replies").apply { start() }
        val current = AtomicReference<Session?>()
        val connected = CompletableFuture<Messenger>()
        val reply = Messenger(Handler(thread.looper) { message ->
            val session = current.get()
            if (session != null && message.sendingUid == helperUid && message.data.getString("nonce") == session.nonce) {
                when (message.what) {
                    3, 5, 7 -> session.result.complete(message.what to Bundle(message.data))
                    4 -> session.retired.complete(Unit)
                }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(Messenger(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                current.get()?.let { session ->
                    val failure = IllegalStateException("P1_STRICT_OWNER_HELPER_DIED")
                    session.result.completeExceptionally(failure)
                    session.retired.completeExceptionally(failure)
                }
            }
        }
        var bound = false
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(helper, "$helper.OwnerProbeService")),
                connection, Context.BIND_AUTO_CREATE)
            check(bound)
            val remote = connected.get(5, TimeUnit.SECONDS)
            fun send(code: Int, payload: Bundle) {
                remote.send(Message.obtain(null, code).apply { data = payload; replyTo = reply })
            }
            fun assertUid(protocol: Int, family: Family, port: Int, uid: Int, aliases: Set<String>) {
                val actual = cm.getConnectionOwnerUid(protocol, InetSocketAddress(family.source, port), destination(family))
                check(actual == uid && context.packageManager.getPackagesForUid(actual)?.toSet() == aliases) {
                    "P1_STRICT_OWNER_TUN_UID protocol=$protocol family=${family.source.address.size} expected=$uid actual=$actual"
                }
            }
            for (family in families) {
                val label = if (family.destination.address.size == 4) "ipv4" else "ipv6"
                val sample = nonce()
                val echo = worker.submit { serve(family, 6, sample) }
                Socket().use { socket ->
                    network.bindSocket(socket)
                    socket.bind(InetSocketAddress(family.source, 0))
                    socket.connect(destination(family), 3000)
                    socket.soTimeout = 3500
                    socket.getOutputStream().write(sample)
                    val received = ByteArray(sample.size)
                    DataInputStream(socket.getInputStream()).readFully(received)
                    check(received.contentEquals(sample))
                    assertUid(6, family, socket.localPort, Process.myUid(), setOf(context.packageName))
                }
                echo.get(5, TimeUnit.SECONDS)
                passed.accept("p1-strict-owner-$scope-main-tcp-$label-real-tun")
                for ((protocol, udpConnected) in listOf(6 to true, 17 to true, 17 to false)) {
                    val payload = nonce()
                    val session = Session(payload.toString(Charsets.US_ASCII))
                    current.set(session)
                    val request = Bundle().apply {
                        putString("nonce", session.nonce)
                        putString("host", if (label == "ipv4") ClientP1StrictOwnerFixture.IPV4 else ClientP1StrictOwnerFixture.IPV6)
                        putInt("port", ClientP1StrictOwnerFixture.PORT)
                        putInt("protocol", protocol)
                        putLong("network", network.networkHandle)
                        putBoolean("connected", udpConnected)
                    }
                    send(6, request)
                    try {
                        if (scope == "both") {
                            serve(family, protocol, payload)
                            val (code, evidence) = session.result.get(5, TimeUnit.SECONDS)
                            check(code == 3 && evidence.getInt("uid") == helperUid && evidence.getInt("protocol") == protocol &&
                                evidence.getInt("destinationPort") == ClientP1StrictOwnerFixture.PORT &&
                                evidence.getByteArray("source")?.contentEquals(family.source.address) == true &&
                                evidence.getByteArray("destination")?.contentEquals(family.destination.address) == true) {
                                "P1_STRICT_OWNER_HELPER_ECHO_TUPLE code=$code"
                            }
                            assertUid(protocol, family, evidence.getInt("sourcePort"), helperUid, packages.drop(1).toSet())
                        } else {
                            val (code, _) = session.result.get(5, TimeUnit.SECONDS)
                            check(code == 7) { "P1_STRICT_OWNER_SHARED_UID_BIND_NOT_REFUSED code=$code" }
                            assertNoOrigin(family, protocol)
                        }
                    } finally {
                        send(2, Bundle().apply { putString("nonce", session.nonce) })
                        session.retired.get(5, TimeUnit.SECONDS)
                        current.set(null)
                    }
                    val transport = when { protocol == 6 -> "tcp"; udpConnected -> "udp-connected"; else -> "udp-unconnected" }
                    passed.accept("p1-strict-owner-$scope-shared-uid-$transport-$label")
                }
            }
            val networkDns = links.dnsServers
            check(networkDns.isNotEmpty() && networkDns.all { it in setOf(InetAddress.getByName("172.19.0.2"),
                InetAddress.getByName("fdfe:dcba:9876::2")) }) { "P1_STRICT_OWNER_NON_OWNED_DNS_SERVER" }
            val lookup = worker.submit<List<InetAddress>> {
                network.getAllByName(ClientP1StrictOwnerFixture.dnsName(fixtureNonce)).toList()
            }
            check(lookup.get(15, TimeUnit.SECONDS) == listOf(InetAddress.getByName(ClientP1StrictOwnerFixture.IPV4))) {
                "P1_STRICT_OWNER_ANDROID_RESOLVER_HOSTS_ANSWER"
            }
            passed.accept("p1-strict-owner-$scope-android-resolver-owned-vpn-hosts")
            check(ClientP1OwnerFixtureScope.packages(context, pin) == packages)
        } finally {
            try { if (bound) context.unbindService(connection) }
            finally {
                thread.quitSafely()
                thread.join(3000)
                check(!thread.isAlive)
            }
        }
    }

    private fun assertNoOrigin(family: Family, protocol: Int) {
        try {
            if (protocol == 6) {
                family.tcp.soTimeout = 300
                family.tcp.accept().use { error("P1_STRICT_OWNER_DENIED_UID_REACHED_TCP_ORIGIN") }
            } else {
                family.udp.soTimeout = 300
                family.udp.receive(DatagramPacket(ByteArray(1), 1))
                error("P1_STRICT_OWNER_DENIED_UID_REACHED_UDP_ORIGIN")
            }
        } catch (_: SocketTimeoutException) {
            // Only accept/receive timeout counts as no origin contact.
        }
    }

    private fun serve(family: Family, protocol: Int, expected: ByteArray) {
        if (protocol == 6) {
            family.tcp.soTimeout = 4000
            family.tcp.accept().use { socket ->
                check(socket.inetAddress == family.loopback)
                socket.soTimeout = 3000
                val received = ByteArray(expected.size)
                DataInputStream(socket.getInputStream()).readFully(received)
                check(received.contentEquals(expected))
                socket.getOutputStream().write(received)
            }
        } else {
            family.udp.soTimeout = 4000
            val received = DatagramPacket(ByteArray(expected.size + 1), expected.size + 1)
            family.udp.receive(received)
            check(received.address == family.loopback && received.length == expected.size &&
                received.data.copyOf(received.length).contentEquals(expected))
            family.udp.send(DatagramPacket(expected, expected.size, received.socketAddress))
        }
    }

    override fun close() {
        try { families.forEach(Family::close) }
        finally {
            worker.shutdownNow()
            check(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private companion object {
        fun destination(family: Family) = InetSocketAddress(family.destination, ClientP1StrictOwnerFixture.PORT)
        fun nonce(): ByteArray = UUID.randomUUID().toString().replace("-", "").toByteArray(Charsets.US_ASCII)

        fun openFamily(destination: String, source: String, loopback: String): Family {
            val dst = InetAddress.getByName(destination)
            val local = InetAddress.getByName(source)
            val listener = InetAddress.getByName(loopback)
            val tcp = ServerSocket()
            val udp = DatagramSocket(null)
            return try {
                tcp.bind(InetSocketAddress(listener, 0), 1)
                udp.bind(InetSocketAddress(listener, 0))
                Family(dst, local, listener, tcp, udp)
            } catch (failure: Throwable) {
                try { tcp.close() } finally { udp.close() }
                throw failure
            }
        }
    }
}
