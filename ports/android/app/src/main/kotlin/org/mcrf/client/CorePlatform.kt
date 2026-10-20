package org.mcrf.client

import android.net.*
import android.os.*
import android.system.OsConstants
import io.nekohasekai.libbox.*
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import io.nekohasekai.libbox.NetworkInterface as CoreInterface

/** Physical-network and synchronous DNS bridge, shared by VPN and isolated probe processes. */
abstract class CorePlatform : VpnService(), PlatformInterface {
    protected var vpnMode = false
    protected var tunnel: ParcelFileDescriptor? = null
    private lateinit var connectivity: ConnectivityManager
    private val dnsWorker = Executors.newFixedThreadPool(2)
    private val dnsCallbacks=DrainingCallbackExecutor(dnsWorker)
    private val networks = linkedMapOf<Network, LinkProperties>()
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile protected var underlying: Network? = null
    @Volatile private var interfaceListener: InterfaceUpdateListener? = null
    protected open fun onUnderlyingNetworkChanged(network:Network?)=Unit
    override fun onCreate() { super.onCreate(); connectivity = getSystemService(ConnectivityManager::class.java) }
    override fun onDestroy() { dnsWorker.shutdown(); super.onDestroy() }
    protected fun closePlatform(keepTun: Boolean = false) {
        stopMonitor()
        if(!keepTun){try { tunnel?.close() } catch (_: IOException) { }; tunnel = null
            if (vpnMode) try { setUnderlyingNetworks(null) } catch (_: SecurityException) { }}
    }
    override fun openTun(options: TunOptions): Int = throw IOException("TUN выключен.")
    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) {
        if (vpnMode && !protect(fd)) throw IOException("Не удалось защитить сокет от VPN-петли.")
        underlying?.let { network -> ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) } }
    }
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        interfaceListener = listener
        val monitor = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(network, connectivity.getLinkProperties(network))
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update(network, properties)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(network, connectivity.getLinkProperties(network))
            override fun onLost(network: Network) = synchronized(networks) { networks.remove(network); selectUnderlying() }
        }
        callback = monitor
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), monitor)
        synchronized(networks) {
            connectivity.allNetworks.forEach { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                    connectivity.getLinkProperties(network)?.let { networks[network] = it }
            }
            selectUnderlying()
        }
    }
    private fun update(network: Network, properties: LinkProperties?) {
        if (properties != null) synchronized(networks) { networks[network] = properties; selectUnderlying() }
    }
    private fun selectUnderlying() {
        var selected = connectivity.activeNetwork
        if (!networks.containsKey(selected)) selected = networks.keys.maxByOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 100 else 0) + when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 20
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 10
                else -> 0
            }
        }
        val previous=underlying
        underlying = selected
        if (vpnMode) try { setUnderlyingNetworks(selected?.let { arrayOf(it) }) } catch (_: SecurityException) { }
        val listener = interfaceListener ?: return
        val name = networks[selected]?.interfaceName.orEmpty()
        val index = try { java.net.NetworkInterface.getByName(name)?.index ?: 0 } catch (_: Exception) { 0 }
        val caps = selected?.let { connectivity.getNetworkCapabilities(it) }
        listener.updateDefaultInterface(name, index, caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), false)
        if(previous!=selected)onUnderlyingNetworkChanged(selected)
    }
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = stopMonitor()
    private fun stopMonitor() {
        interfaceListener = null; val previous = callback; callback = null
        if (previous != null) try { connectivity.unregisterNetworkCallback(previous) } catch (_: IllegalArgumentException) { }
        synchronized(networks) { networks.clear(); underlying = null }
    }
    override fun getInterfaces(): NetworkInterfaceIterator {
        val devices = connectivity.allNetworks.mapNotNull { network ->
            val properties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            val system = properties.interfaceName?.let(java.net.NetworkInterface::getByName) ?: return@mapNotNull null
            CoreInterface().apply {
                setName(system.name); setIndex(system.index); setMTU(system.mtu)
                setFlags(OsConstants.IFF_UP or OsConstants.IFF_RUNNING or if (system.isLoopback) OsConstants.IFF_LOOPBACK else 0)
                setAddresses(strings(properties.linkAddresses.map { it.toString() }))
                setDNSServer(strings(properties.dnsServers.mapNotNull { it.hostAddress }))
                setType(when { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular; else -> Libbox.InterfaceTypeOther })
                setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
        }
        return object : NetworkInterfaceIterator { private var index = 0; override fun hasNext() = index < devices.size; override fun next() = devices[index++] }
    }
    override fun findConnectionOwner(protocol: Int, source: String, sourcePort: Int, destination: String, destinationPort: Int): ConnectionOwner {
        val uid = connectivity.getConnectionOwnerUid(protocol, InetSocketAddress(source, sourcePort), InetSocketAddress(destination, destinationPort))
        if (uid < 0) throw IOException("Не найдено приложение соединения.")
        return ConnectionOwner().apply { setUserId(uid); setAndroidPackageNames(strings(packageManager.getPackagesForUid(uid)?.toList().orEmpty())) }
    }
    override fun localDNSTransport(): LocalDNSTransport = object : LocalDNSTransport {
        override fun raw() = true
        override fun lookup(context: ExchangeContext, network: String, domain: String): Unit = throw IOException("Используется DNS через основную сеть.")
        override fun exchange(context: ExchangeContext, packet: ByteArray) {
            val network = underlying ?: run { context.errnoCode(OsConstants.ENETUNREACH); return }
            val cancel = CancellationSignal(); val done = CountDownLatch(1); val completed = AtomicBoolean()
            context.onCancel { if (completed.compareAndSet(false, true)) { context.errnoCode(OsConstants.ECANCELED); cancel.cancel(); done.countDown() } }
            DnsResolver.getInstance().rawQuery(network, packet, DnsResolver.FLAG_NO_RETRY, dnsCallbacks, cancel, object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) { if (completed.compareAndSet(false, true)) try { dnsAnswers++; context.rawSuccess(answer) } finally { done.countDown() } }
                override fun onError(error: DnsResolver.DnsException) { if (completed.compareAndSet(false, true)) try { dnsErrors++; dnsErrorCode = error.code; context.errnoCode(OsConstants.EIO) } finally { done.countDown() } }
            })
            try { if (!done.await(8, TimeUnit.SECONDS) && completed.compareAndSet(false, true)) { context.errnoCode(OsConstants.ETIMEDOUT); cancel.cancel() } }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); if (completed.compareAndSet(false, true)) { context.errnoCode(OsConstants.EINTR); cancel.cancel() } }
        }
    }
    override fun useProcFS() = false
    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun readWIFIState(): WIFIState = Libbox.newWIFIState("", "")
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) = Unit
    override fun cancelNotification(id: String, type: Int) = Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun registerMyInterface(name: String) = Unit
    override fun usePlatformShell() = false
    override fun usePlatformBridge() = false
    override fun checkPlatformShell(): Unit = throw unsupported()
    override fun openShellSession(user: PlatformUser, command: String, environment: StringIterator, term: String, rows: Int, columns: Int): ShellSession = throw unsupported()
    override fun lookupUser(name: String): PlatformUser = throw unsupported()
    override fun lookupSFTPServer(): String = throw unsupported()
    override fun readSystemSSHHostKey(): String = throw unsupported()
    override fun tailscaleHostname() = "mcrf-android"
    override fun createBridge(options: BridgeOptions): BridgeSession = throw unsupported()
    protected fun unsupported() = IOException("Функция недоступна на Android.")
    companion object {
        @JvmField @Volatile var dnsErrors = 0
        @JvmField @Volatile var dnsAnswers = 0
        @JvmField @Volatile var dnsErrorCode = 0
        fun strings(values: List<String>): StringIterator = object : StringIterator {
            private var index = 0
            override fun hasNext() = index < values.size
            override fun next() = values[index++]
            override fun len() = values.size
        }
    }
}
