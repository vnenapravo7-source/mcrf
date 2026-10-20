package org.mcrf.client

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.InetAddresses
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Inet6Address
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.mcrf.engine.spi.v1.Platform

/** A run may notify the Binder transport when the service retires it. */
internal interface ClientPrimaryPlatformLifetime : AutoCloseable {
    fun onClose(action: () -> Unit)
}

/** Main-process Android backend. No engine implementation or gomobile types live here. */
abstract class ClientPrimaryHostPlatform : VpnService() {
    protected var vpnMode = false
    @Volatile protected var underlying: Network? = null
    private var underlyingDirectIpv4: Boolean? = null
    @Volatile protected var tunnel: ParcelFileDescriptor? = null
    private var retainedTunPlan: Pair<ParcelFileDescriptor, Platform.TunPlan>? = null

    private lateinit var connectivity: ConnectivityManager
    private val stateLock = Any()
    @Volatile private var active: HostRun? = null
    private val dnsWorker = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64))
    // Rejected final Android DNS callbacks must still drain their resolver-owned resources.
    private val dnsCallbacks = DrainingCallbackExecutor(dnsWorker)
    private val retirementWorker = Executors.newSingleThreadScheduledExecutor()

    protected open fun onUnderlyingNetworkChanged(network: Network?) = Unit

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
    }

    override fun onDestroy() {
        // The service coordinator waits for worker death before closing its original
        // TUN. Destruction retires callbacks immediately without bypassing that order.
        closePlatform(keepTun = true)
        retirementWorker.shutdownNow()
        dnsWorker.shutdown()
        super.onDestroy()
    }

    /** Always retire the run, including DNS and callbacks, even when retaining the host TUN. */
    protected fun closePlatform(keepTun: Boolean = false) {
        active?.close()
        if (!keepTun) {
            val old = synchronized(stateLock) { tunnel.also { tunnel = null;retainedTunPlan = null } }
            closeFd(old)
            if (vpnMode) try { setUnderlyingNetworks(null) } catch (_: SecurityException) { }
        }
    }

    /** A carrier cannot bootstrap through the retired ordinary TUN that captures our UID.
     * Re-establish the SAME capture plan with the carrier UID policy before starting it.
     * No old worker/callback may be alive, and the retained TUN is never closed first. */
    protected fun prepareRetainedTunForNativeWorker(live:()->Boolean, create:(Platform.TunPlan)->ParcelFileDescriptor):Boolean =
        synchronized(stateLock) {
            check(active==null) { "PLATFORM_RUN_CONFLICT" }
            val old=tunnel ?: return@synchronized false
            val saved=retainedTunPlan?.takeIf {it.first===old}?.second
                ?:throw IOException("PLATFORM_RETAINED_PLAN_MISSING")
            RetainedTunTransition.replace(old,live,{create(saved)},{next->
                tunnel=next;retainedTunPlan=next to saved
            },::closeFd)
            true
        }

    /** The caller supplies the authoritative, API-aware TUN/app/UID policy and run stop flag. */
    fun forRun(
        generation: Long,
        stopping: () -> Boolean,
        tun: (Platform.TunPlan) -> ParcelFileDescriptor,
        traffic: (Long, Long) -> Unit
    ): Platform {
        require(generation > 0) { "PLATFORM_GENERATION" }
        active?.close()
        val run = HostRun(generation, stopping, tun, traffic)
        synchronized(stateLock) {
            check(active == null) { "PLATFORM_RUN_CONFLICT" }
            active = run
        }
        try {
            // Physical state is initialized before returning, independently of the module's
            // optional default-interface listener. Missing physical connectivity fails closed.
            run.startPhysicalMonitor()
            synchronized(stateLock) {
                run.checkLive(generation)
                run.watchdog = retirementWorker.scheduleWithFixedDelay({
                    if (!run.live()) run.close()
                }, 0, 100, TimeUnit.MILLISECONDS)
            }
            return run
        } catch (error: Exception) {
            run.close()
            throw error
        }
    }

    private inner class HostRun(
        val generation: Long,
        private val stopping: () -> Boolean,
        private val createTun: (Platform.TunPlan) -> ParcelFileDescriptor,
        private val reportTraffic: (Long, Long) -> Unit
    ) : Platform, ClientPrimaryPlatformLifetime {
        @Volatile private var closed = false
        private var tunClaimed = false
        private var defaultListener: Platform.DefaultListener? = null
        private val networks = linkedMapOf<Network, LinkProperties>()
        private var monitor: ConnectivityManager.NetworkCallback? = null
        var watchdog: ScheduledFuture<*>? = null
        private val closeActions = ArrayList<() -> Unit>()
        private val dnsLock = Any()
        private val dnsGate = ClientPrimaryRuntimeDnsGate(generation)
        private val pendingDns = HashMap<Long, DnsJob>()
        // Read-only DEBUG evidence of this run's actual successful callback path.
        // Never increment at START or for a rejected/stale/partly bound socket.
        private val debugProtectAndBindAcks = java.util.concurrent.atomic.AtomicLong()
        @Volatile private var debugLastBoundNetworkHandle = 0L

        fun live(): Boolean = !closed && active === this && !try { stopping() } catch (_: Exception) { true }

        fun checkLive(value: Long) {
            if (value != generation || !live()) throw IOException("PLATFORM_RETIRED")
        }

        override fun onClose(action: () -> Unit) {
            val invoke = synchronized(stateLock) {
                if (closed) true else {
                    check(closeActions.size < 8) { "PLATFORM_CLOSE_LISTENERS" }
                    closeActions.add(action)
                    false
                }
            }
            if (invoke) action()
        }

        override fun close() {
            val actions: List<() -> Unit>
            val callback: ConnectivityManager.NetworkCallback?
            synchronized(stateLock) {
                if (closed) return
                closed = true
                watchdog?.cancel(false)
                watchdog = null
                callback = monitor
                monitor = null
                defaultListener = null
                networks.clear()
                if (active === this) {
                    active = null
                    underlying = null
                }
                actions = closeActions.toList()
                closeActions.clear()
            }
            val cancelled = synchronized(dnsLock) {
                dnsGate.close()
                pendingDns.values.toList().also { jobs ->
                    pendingDns.clear()
                    jobs.forEach { it.reply = dnsError(OsConstants.ECANCELED); it.done.countDown() }
                }
            }
            cancelled.forEach { try { it.cancel.cancel() } catch (_: Exception) { } }
            if (callback != null) try { connectivity.unregisterNetworkCallback(callback) } catch (_: Exception) { }
            actions.forEach { try { it() } catch (_: Exception) { } }
        }

        fun startPhysicalMonitor() {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = updateNetwork(network)
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = updateNetwork(network, properties)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateNetwork(network)
                override fun onLost(network: Network) {
                    val update = synchronized(stateLock) {
                        if (!live()) return
                        networks.remove(network)
                        selectUnderlying()
                    }
                    publish(update)
                }
            }
            val update = synchronized(stateLock) {
                checkLive(generation)
                monitor = callback
                connectivity.registerNetworkCallback(NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
                connectivity.allNetworks.forEach { network ->
                    if (physical(network)) connectivity.getLinkProperties(network)?.let { networks[network] = it }
                }
                selectUnderlying()
            }
            publish(update)
        }

        private fun physical(network: Network): Boolean {
            val caps = connectivity.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        private fun physicalNetwork(): Network = synchronized(stateLock) {
            checkLive(generation)
            val network = underlying ?: throw IOException("PLATFORM_NO_PHYSICAL_NETWORK")
            if (!physical(network)) throw IOException("PLATFORM_NO_PHYSICAL_NETWORK")
            network
        }

        private fun updateNetwork(network: Network, properties: LinkProperties? = connectivity.getLinkProperties(network)) {
            val update = synchronized(stateLock) {
                if (!live()) return
                if (properties != null && physical(network)) networks[network] = properties else networks.remove(network)
                selectUnderlying()
            }
            publish(update)
        }

        /** Called under stateLock, matching CorePlatform's active/validated/transport preference. */
        private fun selectUnderlying(): NetworkUpdate {
            val selected = connectivity.activeNetwork?.takeIf { networks.containsKey(it) }
                ?: networks.keys.maxByOrNull { network ->
                    val caps = connectivity.getNetworkCapabilities(network)
                    (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 100 else 0) + when {
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 20
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 10
                        else -> 0
                    }
                }
            val directIpv4=networks[selected]?.routes?.none {it.isDefaultRoute&&it.destination.address is Inet6Address}
            // Same Wi-Fi identity can gain/lose its IPv6 default route. Treat that
            // family change as a real network update, not a repeated initial callback.
            val changed = underlying != selected || underlyingDirectIpv4 != directIpv4
            underlyingDirectIpv4=directIpv4
            underlying = selected
            if (vpnMode) try { setUnderlyingNetworks(selected?.let { arrayOf(it) }) } catch (_: SecurityException) { }
            val name = networks[selected]?.interfaceName.orEmpty()
            val index = try { java.net.NetworkInterface.getByName(name)?.index ?: 0 } catch (_: Exception) { 0 }
            val caps = selected?.let(connectivity::getNetworkCapabilities)
            return NetworkUpdate(selected, changed, Platform.DefaultInterface(name, index.coerceAtLeast(0),
                caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), false), defaultListener)
        }

        private fun publish(update: NetworkUpdate) {
            synchronized(stateLock) {
                if (!live() || underlying != update.network) return
                // The transport listener only enqueues a bounded callback; it performs no IPC here.
                if (defaultListener === update.listener) update.listener?.update(generation, update.value)
            }
            if (update.changed && live()) onUnderlyingNetworkChanged(update.network)
        }

        override fun startDefaultMonitor(generation: Long, listener: Platform.DefaultListener) {
            val update = synchronized(stateLock) {
                checkLive(generation)
                defaultListener = listener
                selectUnderlying()
            }
            publish(update)
        }

        override fun closeDefaultMonitor(generation: Long) {
            synchronized(stateLock) {
                if (generation != this.generation || closed) return
                defaultListener = null
            }
            // Keep physical monitoring alive for socket protection and raw DNS.
        }

        override fun openTun(generation: Long, plan: Platform.TunPlan): Int {
            checkLive(generation)
            // Validate transport bounds even for a direct, in-process caller. Builder policy
            // remains exclusively in createTun, supplied by the main service.
            val immutablePlan=ClientPrimaryRuntimeWire.decodeTunPlan(ClientPrimaryRuntimeWire.encodeTunPlan(plan))
            physicalNetwork()
            return synchronized(stateLock) {
                checkLive(generation)
                if (!vpnMode || tunClaimed) throw IOException("PLATFORM_TUN_FORBIDDEN")
                tunClaimed = true
                // Serialize Builder/establish with retirement and the next run: a late
                // callback may not replace a VPN belonging to a newer generation.
                val candidate = createTun(plan)
                val network = underlying
                if (!live() || network == null || !physical(network)) {
                    if (tunnel === candidate) tunnel = null
                    closeFd(candidate)
                    throw IOException("PLATFORM_RETIRED")
                }
                val old = tunnel?.takeUnless { it === candidate }
                tunnel = candidate
                retainedTunPlan = candidate to immutablePlan
                closeFd(old)
                candidate.fd
            }
        }

        override fun protectAndBind(generation: Long, borrowedSocketFd: Int): Boolean = try {
            checkLive(generation)
            val network = physicalNetwork()
            ParcelFileDescriptor.fromFd(borrowedSocketFd).use { duplicate ->
                if (vpnMode && !protect(duplicate.fd)) throw IOException("PLATFORM_PROTECT_FAILED")
                network.bindSocket(duplicate.fileDescriptor)
                synchronized(stateLock) {
                    checkLive(generation)
                    val bound = underlying == network && physical(network)
                    if(bound&&BuildConfig.DEBUG){
                        debugLastBoundNetworkHandle=network.networkHandle
                        debugProtectAndBindAcks.incrementAndGet()
                    }
                    bound
                }
            }
        } catch (_: Exception) { false }

        override fun interfaces(generation: Long): List<Platform.InterfaceInfo> {
            checkLive(generation)
            val values = connectivity.allNetworks.mapNotNull { network ->
                val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
                val system = properties.interfaceName?.let(java.net.NetworkInterface::getByName) ?: return@mapNotNull null
                Platform.InterfaceInfo(system.name, system.index.coerceAtLeast(0), system.mtu.coerceAtLeast(0),
                    OsConstants.IFF_UP or OsConstants.IFF_RUNNING or (if (system.isLoopback) OsConstants.IFF_LOOPBACK else 0),
                    when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        else -> "other"
                    }, !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    properties.linkAddresses.map { it.toString() }, properties.dnsServers.mapNotNull { it.hostAddress })
            }
            checkLive(generation)
            return values
        }

        override fun connectionOwner(generation: Long, query: Platform.ConnectionQuery): Platform.ConnectionOwner {
            checkLive(generation)
            // Never let an owner query resolve a worker-supplied hostname through default DNS.
            if (query.source.length > 128 || query.destination.length > 128) throw IOException("PLATFORM_OWNER_ADDRESS")
            val source = InetSocketAddress(InetAddresses.parseNumericAddress(query.source), query.sourcePort)
            val destination = InetSocketAddress(InetAddresses.parseNumericAddress(query.destination), query.destinationPort)
            val uid = connectivity.getConnectionOwnerUid(query.protocol, source, destination)
            if (uid < 0) throw IOException("PLATFORM_OWNER_MISSING")
            val owner = Platform.ConnectionOwner(uid, packageManager.getPackagesForUid(uid)?.toList().orEmpty())
            if(connectivity.getConnectionOwnerUid(query.protocol,source,destination)!=uid||
                packageManager.getPackagesForUid(uid)?.toSet().orEmpty()!=owner.packageNames.toSet())throw IOException("PLATFORM_OWNER_CHANGED")
            checkLive(generation)
            return owner
        }

        override fun exchangeDns(generation: Long, query: Platform.DnsQuery): Platform.DnsReply {
            checkLive(generation)
            val network = try { physicalNetwork() } catch (_: IOException) { null }
            val job: DnsJob
            val expired = ArrayList<DnsJob>()
            try { synchronized(dnsLock) {
                checkLive(generation)
                val now = SystemClock.elapsedRealtime()
                // Expired Binder callers may not yet have been rescheduled. Reap their
                // resolver resources as well as the gate slots before admitting another.
                val iterator = pendingDns.values.iterator()
                while (iterator.hasNext()) {
                    val previous = iterator.next()
                    if (previous.ticket.deadlineElapsedMillis <= now) {
                        dnsGate.cancel(generation, previous.ticket.requestId, now)
                        iterator.remove()
                        previous.reply = dnsError(OsConstants.ETIMEDOUT)
                        previous.done.countDown()
                        expired.add(previous)
                    }
                }
                val admitted = dnsGate.begin(generation, query.requestId, SystemClock.elapsedRealtime(), query.timeoutMillis)
                if (admitted !is ClientPrimaryRuntimeDnsGate.BeginResult.Admitted) {
                    val reason = (admitted as ClientPrimaryRuntimeDnsGate.BeginResult.Rejected).reason
                    return dnsError(when (reason) {
                        ClientPrimaryRuntimeDnsGate.BeginRejection.CANCELLED -> OsConstants.ECANCELED
                        ClientPrimaryRuntimeDnsGate.BeginRejection.TIMED_OUT -> OsConstants.ETIMEDOUT
                        else -> OsConstants.EAGAIN
                    })
                }
                job = DnsJob(admitted.ticket)
                pendingDns[query.requestId] = job
                if (network == null) finishDns(job, dnsError(OsConstants.ENETUNREACH))
                else try {
                    // Admission, cancellation tombstone and dispatch share this lock. In
                    // particular, cancel-before-exchange can never dispatch resolver work.
                    DnsResolver.getInstance().rawQuery(network, query.packet(), DnsResolver.FLAG_NO_RETRY,
                        dnsCallbacks, job.cancel, object : DnsResolver.Callback<ByteArray> {
                            override fun onAnswer(answer: ByteArray, rcode: Int) {
                                val reply = try { Platform.DnsReply(answer, 0, rcode) } catch (_: IllegalArgumentException) { dnsError(OsConstants.EIO) }
                                if (finishDns(job, reply)) dnsAnswers++
                            }
                            override fun onError(error: DnsResolver.DnsException) {
                                if (finishDns(job, dnsError(OsConstants.EIO))) { dnsErrors++; dnsErrorCode = error.code }
                            }
                        })
                } catch (_: Exception) { finishDns(job, dnsError(OsConstants.EIO)) }
            } } finally {
                // CancellationSignal can call Android-owned listeners: never invoke those
                // while holding the admission lock, including on an early rejected request.
                expired.forEach { try { it.cancel.cancel() } catch (_: Exception) { } }
            }
            try {
                val remaining = (job.ticket.deadlineElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                if (!job.done.await(remaining, TimeUnit.MILLISECONDS)) terminateDns(job, OsConstants.ETIMEDOUT)
            } catch (_: InterruptedException) {
                terminateDns(job, OsConstants.EINTR)
                Thread.currentThread().interrupt()
            }
            return job.reply ?: dnsError(OsConstants.ECANCELED)
        }

        private fun finishDns(job: DnsJob, reply: Platform.DnsReply): Boolean = synchronized(dnsLock) {
            if (pendingDns[job.ticket.requestId] !== job) return false
            val result = dnsGate.complete(job.ticket, SystemClock.elapsedRealtime())
            pendingDns.remove(job.ticket.requestId)
            val accepted = result == ClientPrimaryRuntimeDnsGate.CompletionResult.COMPLETED && live()
            job.reply = if (accepted) reply else dnsError(if (result == ClientPrimaryRuntimeDnsGate.CompletionResult.TIMED_OUT)
                OsConstants.ETIMEDOUT else OsConstants.ECANCELED)
            job.done.countDown()
            accepted
        }

        private fun terminateDns(job: DnsJob, errno: Int) {
            synchronized(dnsLock) {
                if (pendingDns[job.ticket.requestId] !== job) return
                dnsGate.cancel(generation, job.ticket.requestId, SystemClock.elapsedRealtime())
                pendingDns.remove(job.ticket.requestId)
                job.reply = dnsError(errno)
                job.done.countDown()
            }
            try { job.cancel.cancel() } catch (_: Exception) { }
        }

        override fun cancelDns(generation: Long, requestId: Long) {
            if (generation != this.generation || requestId <= 0 || closed) return
            val job = synchronized(dnsLock) {
                dnsGate.cancel(generation, requestId, SystemClock.elapsedRealtime())
                pendingDns.remove(requestId)?.also { it.reply = dnsError(OsConstants.ECANCELED); it.done.countDown() }
            }
            try { job?.cancel?.cancel() } catch (_: Exception) { }
        }

        override fun features(generation: Long): Platform.Features { checkLive(generation); return FEATURES }
        override fun wifiState(generation: Long): Platform.WifiState { checkLive(generation); return Platform.WifiState("", "") }
        override fun clearDnsCache(generation: Long) { checkLive(generation) }
        override fun notification(generation: Long, notice: Platform.Notice) { checkLive(generation) }
        override fun cancelNotification(generation: Long, id: String, type: Int) { checkLive(generation) }
        override fun startNeighborMonitor(generation: Long, listener: Platform.NeighborListener) { checkLive(generation) }
        override fun closeNeighborMonitor(generation: Long) = Unit
        override fun registerInterface(generation: Long, name: String) { checkLive(generation) }
        override fun hostname(generation: Long): String { checkLive(generation); return "mcrf-android" }
        override fun traffic(generation: Long, txTotal: Long, rxTotal: Long) {
            synchronized(stateLock) {
                checkLive(generation)
                if (txTotal < 0 || rxTotal < 0) throw IllegalArgumentException("PLATFORM_TRAFFIC")
                reportTraffic(txTotal, rxTotal)
            }
        }
    }

    private data class NetworkUpdate(val network: Network?, val changed: Boolean,
        val value: Platform.DefaultInterface, val listener: Platform.DefaultListener?)

    private class DnsJob(val ticket: ClientPrimaryRuntimeDnsGate.Ticket) {
        val cancel = CancellationSignal()
        val done = CountDownLatch(1)
        @Volatile var reply: Platform.DnsReply? = null
    }

    companion object {
        @JvmField @Volatile var dnsAnswers = 0
        @JvmField @Volatile var dnsErrors = 0
        @JvmField @Volatile var dnsErrorCode = 0
        private val FEATURES = Platform.Features(true, false, false, false,
            Platform.Availability.EMPTY_COMPATIBILITY, Platform.Availability.EMPTY_COMPATIBILITY,
            Platform.Availability.EMPTY_COMPATIBILITY, Platform.Availability.EMPTY_COMPATIBILITY,
            Platform.Availability.UNSUPPORTED, Platform.Availability.UNSUPPORTED)
        private fun dnsError(errno: Int) = Platform.DnsReply(ByteArray(0), errno, 0)
        private fun closeFd(fd: ParcelFileDescriptor?) { try { fd?.close() } catch (_: IOException) { } }
    }
}
