package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

fun interface DiagnosticsComparisonProvider {
    fun measure(serverSlots: Set<Int>, target: ServiceTestTarget, token: DiagnosticCancellation): List<ServerServiceMeasurement>
    fun finishServer() = Unit
}

class DiagnosticsServerComparison(private val provider: DiagnosticsComparisonProvider) {
    fun run(slots: Set<Int>, targetIds: Set<String>, token: DiagnosticCancellation,
        progress: (ServerComparisonReport) -> Unit = {}): ServerComparisonReport {
        require(slots.isNotEmpty() && slots.size <= 1000 && slots.all { it >= 1 })
        require(targetIds.isNotEmpty() && targetIds.size <= ServiceTestCatalog.targets.size)
        require(targetIds.all { ServiceTestCatalog.find(it) != null })
        val targets = ServiceTestCatalog.targets.filter { it.id in targetIds }
        val results = mutableListOf<ServerServiceMeasurement>()
        // Server first: reuse one private ingress for all services, fully retire
        // it before starting the next server. Selection order is preserved.
        for (slot in slots) try {
          for (target in targets) {
            token.check()
            val measured = if (target.check == DiagnosticCheck.UNSUPPORTED) listOf(
                ServerServiceMeasurement(slot, target.id, DiagnosticState.UNAVAILABLE, limitation = ServerComparisonLimit.UNSUPPORTED_SERVICE)
            ) else provider.measure(setOf(slot), target, token)
            token.check()
            require(measured.size == 1 && measured.single().serverSlot == slot && measured.all { it.targetId == target.id })
            require(measured.all { it.state in setOf(DiagnosticState.AVAILABLE, DiagnosticState.ERROR, DiagnosticState.UNAVAILABLE, DiagnosticState.CANCELLED) &&
                (it.state != DiagnosticState.AVAILABLE || it.latencyMs?.let { delay -> delay in 0..60_000 } == true) })
            results.addAll(measured)
            progress(ServerComparisonReport(results.toList(), false))
          }
        } finally { provider.finishServer() }
        token.check()
        return ServerComparisonReport(results.toList(), true)
    }
}

/** Selected-server probes own a disposable authenticated ingress, including BS
 * carriers. They never change the current connection or shared measurement job. */
internal class DiagnosticsNativeComparisonProvider(context: Context, servers: List<ServerImport.Server>) : DiagnosticsComparisonProvider {
    private val app = context.applicationContext
    private val entries = servers.toList()
    private val runtime = DiagnosticsComparisonRuntime { slot, token -> DiagnosticsTemporaryProxy.open(app, entries[slot - 1], token) }
    override fun measure(serverSlots: Set<Int>, target: ServiceTestTarget, token: DiagnosticCancellation): List<ServerServiceMeasurement> {
        require(serverSlots.all { it in 1..entries.size } && ServiceTestCatalog.find(target.id) == target)
        require(serverSlots.size == 1)
        fun unavailable(limit: ServerComparisonLimit) = serverSlots.map {
            ServerServiceMeasurement(it, target.id, DiagnosticState.UNAVAILABLE, limitation = limit)
        }
        token.check()
        if (target.check == DiagnosticCheck.UNSUPPORTED) return unavailable(ServerComparisonLimit.UNSUPPORTED_SERVICE)
        if (SafeModeStore(app).enabled()) return unavailable(ServerComparisonLimit.SAFE_MODE)
        val network = DirectAccessCheck.physical(app) ?: return unavailable(ServerComparisonLimit.NETWORK)
        val manager = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        fun vpnPresent() = manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        // Existing generic sing-box probe doesn't call protect() for a live TUN.
        // Never turn a nested VPN result into a selected-server comparison.
        if (vpnPresent() ||
            McrfVpnService.hasActiveSession()) return unavailable(ServerComparisonLimit.ACTIVE_VPN_ISOLATION)
        if (CaptivePortalMonitor.state(app) != CaptivePortalState.INTERNET) return unavailable(ServerComparisonLimit.INTERNET_UNCONFIRMED)
        if (ProbeCoordinator.measurements != null || ClientPrimaryRetirement.hasPending()) return unavailable(ServerComparisonLimit.BUSY)
        val slot = serverSlots.single()
        val entry = entries[slot - 1]
        if (entry.carrier == null && (entry.needsEngine() || !ProbePlan.supports(entry)))
            return unavailable(ServerComparisonLimit.UNSUPPORTED_PROVIDER)
        val result = runtime.measure(slot, target, token)
        token.check()
        if (DirectAccessCheck.physical(app) != network || vpnPresent() || McrfVpnService.hasActiveSession())
            return unavailable(ServerComparisonLimit.PATH_CHANGED)
        return listOf(result)
    }
    override fun finishServer() = runtime.finishServer()
}

internal interface DiagnosticsComparisonSession {
    val probe: ServiceProbe
    fun refresh(): Boolean = true
    fun awaitClosed()
}

/** One startup attempt per server. A failed ingress is retained as a setup
 * failure for later targets, never retried N times or labelled a service failure. */
internal class DiagnosticsComparisonRuntime(private val open: (Int, DiagnosticCancellation) -> DiagnosticsComparisonSession) {
    private var session: DiagnosticsComparisonSession? = null
    private var slot: Int? = null
    private var startupFailure: ServerComparisonLimit? = null
    fun measure(serverSlot: Int, target: ServiceTestTarget, token: DiagnosticCancellation): ServerServiceMeasurement {
        token.check()
        check(slot == null || slot == serverSlot)
        slot = serverSlot
        fun unavailable(limit: ServerComparisonLimit) = ServerServiceMeasurement(serverSlot, target.id, DiagnosticState.UNAVAILABLE, limitation = limit)
        startupFailure?.let { return unavailable(it) }
        val owned = session ?: try {
            open(serverSlot, token).also { session = it }
        } catch (failure: Exception) {
            token.checkCancellation()
            val limit = when (failure) {
                is java.util.concurrent.CancellationException -> throw failure
                is DiagnosticsProxyStartupException -> failure.limitation
                is java.net.SocketTimeoutException -> ServerComparisonLimit.PROXY_START_TIMEOUT
                else -> ServerComparisonLimit.PROXY_START
            }
            startupFailure = limit
            return unavailable(limit)
        }
        if (!owned.refresh()) {
            startupFailure = ServerComparisonLimit.PROXY_EXITED
            return unavailable(ServerComparisonLimit.PROXY_EXITED)
        }
        val result = withDiagnosticBudget(token, 6000) { child ->
            DiagnosticsPipeline(owned.probe).execute(DiagnosticRequest(target, DiagnosticPath.CURRENT_MCRF), child)
        }
        if (result.proxyFailure == DiagnosticProxyFailure.STOPPED) startupFailure = ServerComparisonLimit.PROXY_EXITED
        return serverServiceMeasurement(serverSlot, result)
    }
    fun finishServer() {
        // If close fails, retain the exact owner and slot for a retry. No next
        // server is admitted while the previous child may still be alive.
        session?.awaitClosed()
        session = null; slot = null; startupFailure = null
    }
}
