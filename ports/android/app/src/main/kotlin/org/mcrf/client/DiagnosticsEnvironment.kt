package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject

internal object DiagnosticsEnvironmentReader {
    fun scope(context: Context): DiagnosticsScope {
        val recovery = McrfVpnService.recovery
        return DiagnosticsScope(DirectAccessCheck.physical(context)?.networkHandle, recovery.epoch, recovery.attempt,
            McrfVpnService.selectedId.ifEmpty { context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("selected", "").orEmpty() },
            McrfVpnService.localProxyPort, ClientPrimarySelection.selected(context),
            listOf("preferences.enc", "routing.enc").joinToString("|") { ProfileStore(context, it).revision() })
    }
    fun capture(context: Context, target: ServiceTestTarget): DiagnosticsEnvironment {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = DirectAccessCheck.physical(context)
        val caps = network?.let(manager::getNetworkCapabilities)
        val installed = ClientPrimarySelection.selected(context)?.let { choice ->
            runCatching { EngineUpdatePrimary.installation(context, if (choice == "singbox") "sing-box" else choice); true }.getOrDefault(false)
        }
        val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val selected = McrfVpnService.selectedId.ifEmpty { prefs.getString("selected", "").orEmpty() }
        val selectedExists = runCatching { Connections(context).apply { load() }.entries().any { it.id == selected } }.getOrDefault(false)
        // Read the encrypted value directly. RoutingStore.load may migrate/write it.
        val route = runCatching {
            val raw = ProfileStore(context, "routing.enc").read()
            if (raw.isBlank()) DiagnosticRouteEvidence(DiagnosticRoutePolicy.UNKNOWN) else savedRoute(JSONObject(raw), target.host)
        }.getOrDefault(DiagnosticRouteEvidence(DiagnosticRoutePolicy.UNKNOWN))
        return DiagnosticsEnvironment(network != null, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            network?.let { manager.getLinkProperties(it)?.routes?.any { route -> route.isDefaultRoute && route.destination.address.address.size == 16 } },
            installed, McrfVpnService.hasActiveSession() && ConnectionPresentation.connected(McrfVpnService.status), selectedExists,
            runCatching { ClientTunStore(context).load().ipv6 }.getOrNull(),
            if (ClientPrimarySelection.selected(context) == "steer") runCatching { ClientPrimarySelection.steerContract(context).ipv6 }.getOrNull() else null,
            route, runCatching { FallbackChainStore(context).load().enabled }.getOrDefault(false))
    }

    /** A deliberately conservative SAVED-policy hint, never an actual egress
     * claim. Unknown earlier conditions prevent a later/default attribution. */
    fun savedRoute(settings: JSONObject, host: String): DiagnosticRouteEvidence {
        if (settings.optJSONArray("vpnApps")?.length()?.let { it > 0 } == true || settings.has("groups"))
            return DiagnosticRouteEvidence(DiagnosticRoutePolicy.UNKNOWN)
        val rules = settings.optJSONArray("rules") ?: return DiagnosticRouteEvidence(DiagnosticRoutePolicy.UNKNOWN)
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            if (!rule.optBoolean("enabled", true)) continue
            if (rule.has("nativeMatch") || rule.has("presetIds") || rule.has("builtinPreset") || rule.optBoolean("invert") || rule.has("serverId"))
                return DiagnosticRouteEvidence(DiagnosticRoutePolicy.UNKNOWN)
            val groups = runCatching { RouteRules.parse(rule.optString("text")) }.getOrNull()
                ?: return DiagnosticRouteEvidence(DiagnosticRoutePolicy.UNKNOWN)
            if (groups.any { (key, values) -> values.isNotEmpty() && key !in setOf("domain", "domain_suffix") })
                return DiagnosticRouteEvidence(DiagnosticRoutePolicy.UNKNOWN)
            if (groups.values.all { it.isEmpty() }) return DiagnosticRouteEvidence(DiagnosticRoutePolicy.UNKNOWN)
            val matches = groups["domain"].orEmpty().any { it.equals(host, true) } ||
                groups["domain_suffix"].orEmpty().any { host.equals(it, true) || host.endsWith(".$it", true) }
            if (matches) return DiagnosticRouteEvidence(policy(rule.optString("target")), index + 1)
        }
        return DiagnosticRouteEvidence(policy(settings.optString("defaultTarget")))
    }
    private fun policy(value: String) = when (value) {
        "block" -> DiagnosticRoutePolicy.BLOCK
        "direct" -> DiagnosticRoutePolicy.DIRECT
        "proxy" -> DiagnosticRoutePolicy.PROXY
        else -> DiagnosticRoutePolicy.UNKNOWN
    }
}
