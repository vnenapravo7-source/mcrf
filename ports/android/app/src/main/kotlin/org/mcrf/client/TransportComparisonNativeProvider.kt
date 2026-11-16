package org.mcrf.client

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only catalogue snapshots + existing engine diagnostic worker; no live SELECT or saved probes. */
class TransportComparisonNativeProvider(context: Context) : TransportComparisonProvider {
    private val app = context.applicationContext
    private var cancellation: Long? = null
    private var identity: String? = null
    override fun measure(profile: ServerImport.Server, token: TransportComparisonCancellation): Long? {
        token.check()
        val captured = cancellation ?: ProbeCoordinator.cancellationToken().also { cancellation = it }
        val origin = identity ?: ProbeService.transportComparisonIdentity(app).also { identity = it }
        return try {
            ProbeService.transportComparisonDiagnostic(app, profile, captured, origin)
        } catch (error: RuntimeException) {
            // A freshly created diagnostic service can still be releasing its first
            // foreground/core lifecycle when the process was cold-started.  Retry only
            // that explicit transient once; ordinary engine/configuration errors remain
            // visible and are never hidden by a retry loop.
            val transient = error.message.orEmpty().let {
                it.contains("TRANSPORT_COMPARISON_PROVIDER_UNAVAILABLE") ||
                    it.contains("Предыдущий замер ещё выполняется")
            }
            if (!transient) throw error
            repeat(8) {
                token.check()
                Thread.sleep(100)
            }
            token.check()
            ProbeService.transportComparisonDiagnostic(app, profile, captured, origin)
        }.also { token.check() }
    }
    companion object {
        private val lease = AtomicBoolean()
        fun ownsConnection() = McrfVpnService.hasActiveSession() || McrfVpnService.cleanupPending() || McrfVpnService.wifiPaused()
        @Suppress("DEPRECATION")
        fun connectionBlocked(context: Context): Boolean = ownsConnection() || runCatching {
            val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
            // Match ProbeService admission, including a VPN that is not default.
            manager.allNetworks.any {
                manager.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(true)
        fun run(context: Context, server: ServerImport.Server, profiles: List<ServerImport.Server>, token: TransportComparisonCancellation,
            progress: (TransportComparisonReport) -> Unit): TransportComparisonReport {
            check(lease.compareAndSet(false, true)) { "A/B уже выполняется." }
            try {
                // JSON objects in the catalogue are mutable; detach them before native work.
                fun copy(entry: ServerImport.Server) = ServerImport.Server(entry.original, entry.name, entry.protocol, entry.host, entry.port,
                    entry.outbound?.let { JSONObject(it.toString()) }, entry.endpoint?.let { JSONObject(it.toString()) },
                    entry.configuration?.let { JSONObject(it.toString()) }, entry.carrier?.let { JSONObject(it.toString()) }, entry.profileDns.toList(), entry.whitelists)
                val snapshots = profiles.map(::copy)
                return TransportComparison(TransportComparisonNativeProvider(context), SystemClock::elapsedRealtime)
                    .run(copy(server), snapshots, token, progress)
            } finally { lease.set(false) }
        }
    }
}
