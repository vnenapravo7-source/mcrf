package org.mcrf.client

import android.content.Context
import android.net.Network
import android.os.SystemClock
import org.json.JSONObject

/** All Beta chain storage, admission and evidence stay outside the VPN service's start path. */
internal class FallbackChainController(private val context: Context) {
    data class Plan(val template: JSONObject, val serverId: String, val network: Network)
    private val runtime = FallbackChainRuntime()
    private val preferences get() = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
    fun reset() = runtime.reset()
    fun clearEvidence() = runtime.clearEvidence()
    private fun scope(applyRoutes: Boolean) = ProfilePolicyRuntime.active(context)?.configuration==null&&!SafeModeStore(context).enabled() &&
        preferences.getBoolean("catalogueProfile", false) && !preferences.getBoolean("automatic", false) &&
        (!applyRoutes || !RoutingConnectionBinding.enabled(RoutingStore(context).apply { load() }.snapshot()))

    fun valid(plan: Plan) = ProfilePolicyRuntime.active(context)?.configuration==null&&DirectAccessCheck.physical(context) == plan.network &&
        CaptivePortalMonitor.state(context) == CaptivePortalState.INTERNET

    fun next(source: JSONObject, applyRoutes: Boolean, selected: String): Plan? {
        val settings = try { FallbackChainStore(context).load() } catch (_: Exception) { return null }
        if (!settings.enabled || settings.vpn.isEmpty() && settings.bs.isEmpty() || !scope(applyRoutes)) return null
        val network = DirectAccessCheck.physical(context) ?: return null
        if (CaptivePortalMonitor.state(context) != CaptivePortalState.INTERNET) return null
        val entries = Connections(context).apply { load() }.entries()
        val current = selected.ifEmpty { preferences.getString("selected", "").orEmpty() }
        val entry = entries.firstOrNull { it.id == current } ?: return null
        val bs = entry.whitelists
        val candidates = ConnectionSections.entries(entries, bs).filter { it.configuration == null }.associateBy { it.id }
        val ids = settings.ids(bs).filter { it in candidates }
        val next = runtime.policy.next(if (bs) "bs" else "vpn", ids, current,
            preferences.getString("selected", "").orEmpty(), network.networkHandle,
            true, true, false, SystemClock.elapsedRealtime()) ?: return null
        val replacement = candidates.getValue(next)
        val template = ServerImport.build(listOf(replacement), next, false, preferences.getInt("interval", 60),
            AutoSelectionDeviation.millis(preferences), "Cloudflare")
        source.optJSONObject("dns")?.let { template.put("dns", JSONObject(it.toString())) }
        if (applyRoutes) DnsConfig.applyGenerated(template, RoutingStore(context).apply { load() }.snapshot().getJSONObject("globalDns"))
        return Plan(template, next, network).takeIf { valid(it) }
    }

    fun confirmedFailure(applyRoutes: Boolean, selected: String, health: (String) -> Long, cancelled: () -> Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (runtime.policy.exhausted(now) || !runtime.due(now)) return false
        val settings = try { FallbackChainStore(context).load() } catch (_: Exception) {
            runtime.clearEvidence(); return false
        }
        val ids = if (selected in settings.vpn) settings.vpn else settings.bs
        // With no successor, this feature must not introduce new checks or reconnects.
        if (!settings.enabled || selected !in ids || ids.indexOf(selected) == ids.lastIndex ||
            ProbeCoordinator.measurements != null || !scope(applyRoutes)) {
            runtime.clearEvidence(); return false
        }
        val network = DirectAccessCheck.physical(context)
        if (network == null || CaptivePortalMonitor.state(context) != CaptivePortalState.INTERNET) {
            runtime.clearEvidence(); return false
        }
        // Two origins and two time-separated samples; one blocked site never proves a failed connection.
        val healthy = try {
            health("https://www.gstatic.com/generate_204") >= 0 || health("https://cp.cloudflare.com/generate_204") >= 0
        } catch (_: Exception) {
            // An unsupported worker command or failed IPC is not remote-connection evidence.
            runtime.clearEvidence(); return false
        }
        if (cancelled() || DirectAccessCheck.physical(context) != network ||
            CaptivePortalMonitor.state(context) != CaptivePortalState.INTERNET) {
            runtime.clearEvidence(); return false
        }
        return runtime.report(selected, network.networkHandle, healthy)
    }
}
