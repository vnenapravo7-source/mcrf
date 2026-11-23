package org.mcrf.client

import android.content.Context
import android.net.*
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.engine.spi.v1.Platform
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/** Private authenticated loopback ingress; no VPN, selection, preference writes,
 * downloads or shared ProbeCoordinator cancellation. One exact owner at a time. */
internal class DiagnosticsTemporaryProxy private constructor(
    override val probe: RealServiceProbe,
    internal val endpoint: DiagnosticsOwnedEndpoint,
    private val lifetime: DiagnosticsProxyLifetime,
    private val attachment: AutoCloseable
) : AutoCloseable, DiagnosticsComparisonSession {
    override fun refresh() = lifetime.refresh()
    override fun close() { try { lifetime.close() } finally { attachment.close() } }
    override fun awaitClosed() { close(); lifetime.awaitClosed() }
    companion object {
        private val leased = AtomicBoolean()
        fun open(context: Context, server: ServerImport.Server, token: DiagnosticCancellation): DiagnosticsTemporaryProxy {
            token.check()
            val app = context.applicationContext
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val network = DirectAccessCheck.physical(app) ?: throw DiagnosticsProxyStartupException(ServerComparisonLimit.NETWORK)
            @Suppress("DEPRECATION")
            fun live() = DirectAccessCheck.physical(app) == network && !McrfVpnService.hasActiveSession() &&
                manager.allNetworks.none { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            if (SafeModeStore(app).enabled()) throw DiagnosticsProxyStartupException(ServerComparisonLimit.SAFE_MODE)
            if (!live()) throw DiagnosticsProxyStartupException(ServerComparisonLimit.PATH_CHANGED)
            if (ProbeCoordinator.measurements != null || ClientPrimaryRetirement.hasPending() || !leased.compareAndSet(false, true))
                throw DiagnosticsProxyStartupException(ServerComparisonLimit.BUSY)
            val lifetime = DiagnosticsProxyLifetime(120_000, AutoCloseable { leased.set(false) })
            var attachment: AutoCloseable? = null
            var startupLimit = ServerComparisonLimit.PROXY_CONFIG
            try {
                attachment = token.attach(lifetime)
                fun checkLive() {
                    token.check()
                    if (!live()) throw DiagnosticsProxyStartupException(ServerComparisonLimit.PATH_CHANGED)
                    if (lifetime.isStopped()) throw DiagnosticsProxyStartupException(ServerComparisonLimit.PROXY_EXITED)
                }
                checkLive()
                val port: Int
                val user: String
                val password: String
                val socks: Boolean
                val childLive: () -> Boolean
                if (server.carrier != null) {
                    startupLimit = ServerComparisonLimit.PROXY_START
                    val startupMillis = minOf(30_000L, token.remainingMs().toLong())
                    if (startupMillis < 1000) throw java.net.SocketTimeoutException()
                    val carrier = lifetime.adopt(NativeCarrier.startBackground(app, org.json.JSONObject(server.carrier.toString()),
                        BooleanSupplier { lifetime.isStopped() || !live() || runCatching { token.check() }.isFailure }, startupMillis))
                    port = carrier.port; user = carrier.username; password = carrier.password; socks = true
                    childLive = { carrier.runtimeFailure() == null }
                } else {
                    if (server.needsEngine() || !ProbePlan.supports(server))
                        throw DiagnosticsProxyStartupException(ServerComparisonLimit.UNSUPPORTED_PROVIDER)
                    // This disposable worker explicitly owns sing-box, regardless
                    // of the app's selected primary. No choice/preferences change.
                    try { EngineUpdatePrimary.installation(app, "sing-box") }
                    catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        throw DiagnosticsProxyStartupException(ServerComparisonLimit.SINGBOX_MISSING)
                    } catch (_: Exception) {
                        token.checkCancellation()
                        throw DiagnosticsProxyStartupException(ServerComparisonLimit.SINGBOX_UNVERIFIED)
                    }
                    val generation = System.nanoTime().coerceAtLeast(1)
                    val platform = PhysicalProxyPlatform(app, network, generation) { checkLive() }
                    lifetime.adopt(platform)
                    port = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
                    val config = ClientPrimaryProbeConfig.isolated(ServerImport.build(listOf(server), server.id, false, 60, 50, "Системный"), port)
                    val auth = config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("users").getJSONObject(0)
                    user = auth.getString("username"); password = auth.getString("password"); socks = false
                    val settings = ClientPrimarySelection.envelope(config, false, false)
                    startupLimit = ServerComparisonLimit.PROXY_START
                    val worker = try {
                        ClientPrimaryWorker.connect(app, "singbox", generation, platform, ClientPrimaryProbeWorkerService::class.java)
                    } catch (pending: ClientPrimaryWorker.CleanupPending) {
                        lifetime.adopt(pending.worker) { pending.worker.requestStop() }; throw pending
                    }
                    lifetime.adopt(worker) { worker.requestStop() }
                    checkLive()
                    startupLimit = ServerComparisonLimit.PROXY_VALIDATION
                    if (!worker.validate("", settings, Engine.Mode.PROXY).supported)
                        throw DiagnosticsProxyStartupException(ServerComparisonLimit.PROXY_VALIDATION)
                    startupLimit = ServerComparisonLimit.PROXY_START
                    checkLive(); worker.start("", settings, Engine.Mode.PROXY); checkLive()
                    childLive = { worker.state == "RUNNING" }
                }
                checkLive()
                val bytes = "$user:$password".toByteArray(Charsets.UTF_8)
                val authorization = try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
                val probe = RealServiceProbe(network, { live() }, port, authorization,
                    { live() }, if (socks) user to password else null,
                    { !lifetime.isStopped() && childLive() })
                val endpoint = DiagnosticsOwnedEndpoint(port, user, password, socks) {
                    checkLive()
                    if (!childLive()) throw DiagnosticsProxyStartupException(ServerComparisonLimit.PROXY_EXITED)
                }
                return DiagnosticsTemporaryProxy(probe, endpoint, lifetime, attachment)
            } catch (failure: Throwable) {
                // Complete retirement before releasing the lease or reporting a
                // startup failure. Cancellation/timeout cannot abandon a late child.
                try { lifetime.close() } finally { attachment?.close(); lifetime.startupFinished() }
                lifetime.awaitClosed()
                if (failure !is Exception) throw failure
                token.checkCancellation()
                if (failure is DiagnosticsProxyStartupException) throw failure
                if (!live()) throw DiagnosticsProxyStartupException(ServerComparisonLimit.PATH_CHANGED)
                if (failure is java.net.SocketTimeoutException || runCatching { token.check() }.isFailure)
                    throw DiagnosticsProxyStartupException(ServerComparisonLimit.PROXY_START_TIMEOUT)
                if (failure is ClientOptionalCoreRequired || failure is android.content.pm.PackageManager.NameNotFoundException)
                    throw DiagnosticsProxyStartupException(ServerComparisonLimit.UNSUPPORTED_PROVIDER)
                throw DiagnosticsProxyStartupException(startupLimit)
            } finally { lifetime.startupFinished() }
        }
    }
}

/** Pinned physical-network bridge. A network/VPN change invalidates the probe;
 * it cannot open a TUN or borrow/stop a platform belonging to an active session. */
internal class PhysicalProxyPlatform(context: Context, private val network: Network,
    private val generation: Long, private val checkParent: () -> Unit) : Platform, AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val closed = AtomicBoolean()
    private val queries = ConcurrentHashMap<Long, CancellationSignal>()
    private fun live(gen: Long) { checkParent(); if (closed.get() || gen != generation) throw IOException() }
    override fun close() { closed.set(true); queries.values.forEach { it.cancel() } }
    override fun protectAndBind(generation: Long, borrowedSocketFd: Int): Boolean = runCatching {
        live(generation)
        ParcelFileDescriptor.fromFd(borrowedSocketFd).use { network.bindSocket(it.fileDescriptor) }
        live(generation); true
    }.getOrDefault(false)
    override fun interfaces(generation: Long): List<Platform.InterfaceInfo> {
        live(generation)
        val properties = manager.getLinkProperties(network) ?: throw IOException()
        val caps = manager.getNetworkCapabilities(network) ?: throw IOException()
        val system = properties.interfaceName?.let(NetworkInterface::getByName) ?: throw IOException()
        return listOf(Platform.InterfaceInfo(system.name, system.index.coerceAtLeast(0), system.mtu.coerceAtLeast(0),
            OsConstants.IFF_UP or OsConstants.IFF_RUNNING, when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }, !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            properties.linkAddresses.map { it.toString() }, properties.dnsServers.mapNotNull { it.hostAddress }))
    }
    override fun startDefaultMonitor(generation: Long, listener: Platform.DefaultListener) {
        val entry = interfaces(generation).single()
        listener.update(generation, Platform.DefaultInterface(entry.name, entry.index, entry.metered, false))
    }
    override fun closeDefaultMonitor(generation: Long) = Unit
    @Suppress("DEPRECATION")
    override fun exchangeDns(generation: Long, query: Platform.DnsQuery): Platform.DnsReply {
        live(generation)
        check(queries.size < 8)
        val cancellation = CancellationSignal()
        check(queries.putIfAbsent(query.requestId, cancellation) == null)
        val done = CountDownLatch(1)
        var reply = Platform.DnsReply(ByteArray(0), OsConstants.EIO, 0)
        try {
            live(generation)
            DnsResolver.getInstance().rawQuery(network, query.packet(), DnsResolver.FLAG_NO_RETRY, { it.run() }, cancellation,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) { reply = Platform.DnsReply(answer, 0, rcode); done.countDown() }
                    override fun onError(error: DnsResolver.DnsException) { done.countDown() }
                })
            if (!done.await(query.timeoutMillis.toLong().coerceIn(1, 6000), TimeUnit.MILLISECONDS)) throw IOException()
            live(generation); return reply
        } finally { queries.remove(query.requestId, cancellation); cancellation.cancel() }
    }
    override fun cancelDns(generation: Long, requestId: Long) { if (generation == this.generation) queries[requestId]?.cancel() }
    override fun features(generation: Long): Platform.Features {
        live(generation)
        return Platform.Features(true, false, false, false, Platform.Availability.EMPTY_COMPATIBILITY, Platform.Availability.UNSUPPORTED,
            Platform.Availability.UNSUPPORTED, Platform.Availability.UNSUPPORTED, Platform.Availability.UNSUPPORTED, Platform.Availability.UNSUPPORTED)
    }
    private fun denied(): Nothing = throw IOException()
    override fun openTun(generation: Long, plan: Platform.TunPlan): Int = denied()
    override fun connectionOwner(generation: Long, query: Platform.ConnectionQuery): Platform.ConnectionOwner = denied()
    // libbox reads this optional metadata unconditionally during startup. No
    // Wi-Fi identity is exposed by a physical-network probe; lifecycle checks
    // still reject a cancelled, retired or mismatched generation.
    override fun wifiState(generation: Long): Platform.WifiState { live(generation); return Platform.WifiState("", "") }
    override fun clearDnsCache(generation: Long): Unit = denied()
    override fun notification(generation: Long, notice: Platform.Notice): Unit = denied()
    override fun cancelNotification(generation: Long, id: String, type: Int) = Unit
    override fun startNeighborMonitor(generation: Long, listener: Platform.NeighborListener): Unit = denied()
    override fun closeNeighborMonitor(generation: Long) = Unit
    override fun registerInterface(generation: Long, name: String): Unit = denied()
    override fun hostname(generation: Long): String = denied()
    override fun traffic(generation: Long, txTotal: Long, rxTotal: Long) = Unit
}
