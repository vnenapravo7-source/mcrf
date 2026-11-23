package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.InetAddress
import java.util.Locale

/** Suggestions only. Pairing and helper launch continue to use the fixed loopback transport. */
@Suppress("DEPRECATION")
internal object AnubisAdbDiscovery {
    private const val DURATION_MS = 90_000L
    private const val MAX_SERVICES = 24
    private const val MAX_RESOLUTION_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 400L
    private const val CONNECT = "_adb-tls-connect._tcp"
    private const val PAIRING = "_adb-tls-pairing._tcp"

    data class Ports(val connect: Int? = null, val pairing: Int? = null)

    /** Start from the main thread and close when the dialog is dismissed or submits pairing. */
    fun start(context: Context, onPorts: (Ports) -> Unit): Closeable {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Closeable {}
        val app = context.applicationContext
        val manager = app.getSystemService(NsdManager::class.java) ?: return Closeable {}
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return Closeable {}
        return Session(manager, connectivity, onPorts).apply { start() }
    }

    private data class Service(val type: String, val name: String, val network: Network?)
    private data class Resolved(val port: Int, val hosts: List<InetAddress>, val network: Network?)

    private class Session(
        private val manager: NsdManager,
        private val connectivity: ConnectivityManager,
        callback: (Ports) -> Unit,
    ) : Closeable {
        private val main = Handler(Looper.getMainLooper())
        private val expire = Runnable { close() }
        private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
        private val candidates = linkedMapOf<Service, NsdServiceInfo>()
        private val resolved = mutableMapOf<Service, Resolved>()
        private val attempts = mutableMapOf<Service, Int>()
        private val pending = ArrayDeque<Service>()
        private var resolving: NsdManager.ResolveListener? = null
        private var onPorts: ((Ports) -> Unit)? = callback
        private var last = Ports()

        fun start() {
            main.postDelayed(expire, DURATION_MS)
            for (type in listOf(CONNECT, PAIRING)) {
                val listener = discovery(type)
                listeners[type] = listener
                try {
                    manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (_: RuntimeException) {
                    listeners.remove(type)
                }
            }
            if (listeners.isEmpty()) close()
        }

        private fun discovery(type: String) = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                main.post { if (onPorts == null) stopDiscovery(this) }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { listeners.remove(type, this) }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (onPorts == null || candidates.size >= MAX_SERVICES) return@post
                    val key = identity(type, serviceInfo) ?: return@post
                    if (candidates.putIfAbsent(key, serviceInfo) == null) {
                        pending.addLast(key)
                        resolveNext()
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (onPorts == null) return@post
                    val key = identity(type, serviceInfo) ?: return@post
                    candidates.remove(key)
                    pending.remove(key)
                    attempts.remove(key)
                    if (resolved.remove(key) != null) publish()
                }
            }
        }

        private fun resolveNext() {
            if (onPorts == null || resolving != null) return
            while (pending.isNotEmpty()) {
                val key = pending.removeFirst()
                val candidate = candidates[key] ?: continue
                attempts[key] = (attempts[key] ?: 0) + 1
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = received(null)
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) = received(serviceInfo)

                    private fun received(info: NsdServiceInfo?) {
                        main.post {
                            if (onPorts == null || resolving !== this) return@post
                            resolving = null
                            var published = false
                            if (info != null && candidates[key] === candidate && validResolution(key, info)) {
                                val hosts = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                                if (hosts.size in 1..16) {
                                    resolved[key] = Resolved(info.port, hosts.toList(), networkOf(info) ?: key.network)
                                    publish()
                                    published = true
                                }
                            }
                            if (!published) retry(key, candidate)
                            resolveNext()
                        }
                    }
                }
                resolving = listener
                try {
                    manager.resolveService(candidate, listener)
                    return
                } catch (_: RuntimeException) {
                    resolving = null
                    retry(key, candidate)
                }
            }
        }

        private fun retry(key: Service, candidate: NsdServiceInfo) {
            if (candidates[key] !== candidate || (attempts[key] ?: 0) >= MAX_RESOLUTION_ATTEMPTS) return
            main.postDelayed({
                if (onPorts != null && candidates[key] === candidate) {
                    pending.addLast(key)
                    resolveNext()
                }
            }, RETRY_DELAY_MS)
        }

        private fun publish() {
            val wifi = wifiAddresses()
            fun portOf(type: String): Int? = resolved.asSequence()
                .filter { (key, value) -> key.type == type && value.belongsTo(wifi) }
                .map { (_, value) -> value.port }.distinct().take(2).toList().singleOrNull()
            val connect = portOf(CONNECT)
            val pairing = portOf(PAIRING)
            val ports = if (connect != null && connect == pairing) Ports() else Ports(connect, pairing)
            if (ports != last) {
                last = ports
                onPorts?.invoke(ports)
            }
        }

        private fun wifiAddresses(): Map<Network, Set<InetAddress>> = try {
            connectivity.allNetworks.mapNotNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
                    .map { it.address }.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }
                network to addresses.toSet()
            }.toMap()
        } catch (_: SecurityException) {
            emptyMap()
        }

        private fun stopDiscovery(listener: NsdManager.DiscoveryListener) {
            runCatching { manager.stopServiceDiscovery(listener) }
        }

        override fun close() {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (onPorts == null) return
            onPorts = null
            main.removeCallbacks(expire)
            listeners.values.forEach(::stopDiscovery)
            listeners.clear()
            if (Build.VERSION.SDK_INT >= 34) resolving?.let { runCatching { manager.stopServiceResolution(it) } }
            resolving = null
            pending.clear()
            candidates.clear()
            resolved.clear()
            attempts.clear()
        }
    }

    private fun identity(type: String, info: NsdServiceInfo): Service? {
        val name = info.serviceName ?: return null
        if (normalizeType(info.serviceType) != type || name.length !in 5..63 || !name.startsWith("adb-", ignoreCase = true)) return null
        return Service(type, name.lowercase(Locale.ROOT), networkOf(info))
    }

    private fun validResolution(key: Service, info: NsdServiceInfo): Boolean =
        info.port in 1..65535 && info.serviceName.equals(key.name, ignoreCase = true) && normalizeType(info.serviceType) == key.type &&
            (key.network == null || networkOf(info) == null || networkOf(info) == key.network)

    private fun normalizeType(type: String?): String? = type?.trim('.')?.lowercase(Locale.ROOT)?.removeSuffix(".local")

    private fun networkOf(info: NsdServiceInfo): Network? = if (Build.VERSION.SDK_INT >= 33) info.network else null

    private fun Resolved.belongsTo(wifi: Map<Network, Set<InetAddress>>): Boolean {
        // The Settings advertiser and adbd can return different A/AAAA sets. A match on
        // the reporting Wi-Fi interface suffices because the UI only receives the port,
        // and pairing itself still connects to literal loopback and authenticates the code.
        return if (network == null) wifi.values.any { addresses -> hosts.any(addresses::contains) }
        else wifi[network]?.let { addresses -> hosts.any(addresses::contains) } == true
    }
}
