package org.mcrf.client

import org.json.JSONObject
import org.json.JSONArray

/** Valid native DNS tag is also the integration marker; no unknown root/core options. */
object CompatibilityImportDns {
    const val TAG = "mcrf-profile-dns"
    fun common(entries: List<ServerImport.Server>): List<String> {
        val lists = entries.map { it.profileDns }
        return lists.firstOrNull()?.takeIf { it.isNotEmpty() && lists.all { other -> other == it } }.orEmpty()
    }
    fun install(config: JSONObject, resolvers: List<String>): JSONObject {
        if (resolvers.isEmpty()) return config
        require(resolvers.size <= 8 && resolvers.all { RouteRules.literal(it) != null }) { "DNS профиля должен содержать IP-адреса." }
        // sing-box has no DNS server-group fallback here. Explicitly use only the first imported resolver.
        val dns = config.optJSONObject("dns") ?: JSONObject().also { config.put("dns", it) }
        val existing = dns.optJSONArray("servers") ?: JSONArray()
        val servers = JSONArray().put(JSONObject().put("type", "udp").put("tag", TAG).put("server", resolvers.first()).put("server_port", 53).put("detour", config.getJSONObject("route").getString("final")))
        repeat(existing.length()) { i -> val node = existing.getJSONObject(i); if (node.optString("tag") != TAG) servers.put(node) }
        dns.put("servers", servers).put("final", TAG)
        config.getJSONObject("route").put("default_domain_resolver", TAG)
        return config
    }
    fun hasOverride(config: JSONObject): Boolean = config.optJSONObject("dns")?.optJSONArray("servers")?.let { servers ->
        (0 until servers.length()).any { servers.optJSONObject(it)?.optString("tag") == TAG }
    } ?: false
    /** After advanced DNS compilation: retain rule-specific DNS, restore the profile's default only. */
    fun enforce(config: JSONObject) {
        if (hasOverride(config)) {
            val dns = config.getJSONObject("dns"); dns.put("final", TAG)
            dns.optJSONArray("rules")?.let { rules -> repeat(rules.length()) { i ->
                val rule = rules.getJSONObject(i)
                if (rule.optString("action") == "route" && rule.keys().asSequence().all { it in setOf("action", "server", "strategy", "disable_cache", "rewrite_ttl", "client_subnet") }) rule.put("server", TAG)
            } }
            config.getJSONObject("route").put("default_domain_resolver", TAG)
        }
    }
}
