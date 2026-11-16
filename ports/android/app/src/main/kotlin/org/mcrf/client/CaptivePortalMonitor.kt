package org.mcrf.client

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Platform detection is passive. The single fixed HTTPS request is exclusively user initiated.
 * https://developer.android.com/develop/connectivity/network-ops/reading-network-state */
object CaptivePortalMonitor {
    private val lock = Any()
    private val owners = linkedSetOf<Any>()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var watchedManager: ConnectivityManager? = null
    private var sampleNetwork: Network? = null
    private var sampleAt = 0L
    private var sample: CaptivePortalState? = null
    private var sampleGeneration = 0L
    private val busy = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mcrf-portal-probe").apply { isDaemon = true } }

    // Retired feature: retain passive Android state for diagnostics, never run its active detector.
    fun enabled(context: Context) = false

    fun state(context: Context): CaptivePortalState = try {
        val network = DirectAccessCheck.physical(context)
        val caps = network?.let { context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(it) }
        val system = CaptivePortalPolicy.system(caps != null,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        synchronized(lock) {
            if (system == CaptivePortalState.PORTAL || system == CaptivePortalState.OFFLINE ||
                sampleNetwork != network || SystemClock.elapsedRealtime() - sampleAt !in 0..CaptivePortalPolicy.PROBE_TTL_MS) {
                sample = null
            }
            if (system == CaptivePortalState.PORTAL || system == CaptivePortalState.OFFLINE) system
            else sample ?: system
        }
    } catch (_: Exception) { CaptivePortalState.UNKNOWN }

    fun blocksAutomatic(context: Context) = enabled(context) && CaptivePortalPolicy.pause(state(context))

    fun watch(context: Context): AutoCloseable {
        if(!enabled(context))return AutoCloseable {}
        val app = context.applicationContext
        val owner = Any()
        synchronized(lock) {
            owners.add(owner)
            if (callback == null) {
                val manager = app.getSystemService(ConnectivityManager::class.java)
                val listener = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        synchronized(lock) { sampleGeneration++; sample = null }
                        changed(app)
                    }
                    override fun onLost(network: Network) {
                        synchronized(lock) { sampleGeneration++; sample = null }
                        changed(app)
                    }
                }
                try {
                    manager.registerNetworkCallback(NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), listener)
                    callback = listener; watchedManager = manager
                } catch (_: Exception) { /* The card remains available for an explicit check. */ }
            }
        }
        return AutoCloseable { synchronized(lock) {
            owners.remove(owner)
            if (owners.isEmpty()) {
                callback?.let { runCatching { watchedManager?.unregisterNetworkCallback(it) } }
                callback = null; watchedManager = null; sample = null; sampleNetwork = null; sampleGeneration++
            }
        } }
    }

    private fun changed(context: Context) { state(context); ProbeCoordinator.presentationChanged() }

    /** Never follows a portal URL or reads a response body; TLS validation stays enabled.
     * HTTPS deliberately honours the app's no-cleartext policy. A TLS failure is inconclusive. */
    fun probe(context: Context, complete: () -> Unit): Boolean {
        if (!enabled(context) || !busy.compareAndSet(false, true)) return false
        val app = context.applicationContext
        worker.execute {
            try {
                val network = DirectAccessCheck.physical(app) ?: return@execute
                val generation = synchronized(lock) { sampleGeneration }
                var connection: HttpURLConnection? = null
                val result = try {
                    val request = network.openConnection(URL("https://www.gstatic.com/generate_204"), Proxy.NO_PROXY) as HttpURLConnection
                    connection = request
                    request.apply {
                        connectTimeout = 2500; readTimeout = 2500; instanceFollowRedirects = false
                        useCaches = false; allowUserInteraction = false
                        setRequestProperty("Cookie", ""); setRequestProperty("Authorization", "")
                        setRequestProperty("User-Agent", "MCRF-Connectivity")
                    }
                    CaptivePortalPolicy.probe(request.responseCode)
                } catch (_: Exception) { CaptivePortalState.UNVALIDATED }
                finally { connection?.disconnect() }
                if (enabled(app) && DirectAccessCheck.physical(app) == network) synchronized(lock) {
                    if (generation == sampleGeneration) {
                        sampleNetwork = network; sampleAt = SystemClock.elapsedRealtime(); sample = result
                    }
                }
            } catch (_: Exception) { /* No endpoint, redirect or exception details are retained. */ }
            finally { busy.set(false); changed(app); complete() }
        }
        return true
    }

    fun openLogin(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = DirectAccessCheck.physical(context)
        if (network != null && manager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true) {
            try {
                // Public since API 23, but absent from several OEM compile stubs.
                ConnectivityManager::class.java
                    .getMethod("startCaptivePortalApp", Network::class.java)
                    .invoke(manager, network)
                return
            } catch (_: Exception) { }
        }
        // No arbitrary redirect, process-wide binding, VPN stop or automatic browser launch.
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
