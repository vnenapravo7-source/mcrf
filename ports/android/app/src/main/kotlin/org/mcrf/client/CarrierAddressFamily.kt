package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Runtime copy only. The pinned CSQTT/QWDTT RAW/SOCKS bridges have IPv4-only inner stacks.
 * A preference for IPv4 still returns AAAA, producing accepted TUN TCP connections which
 * then fail inside that bridge. Do not remove the IPv6 TUN/leak barrier or bypass the VPN.
 */
internal object CarrierAddressFamily {
    const val NOTE = "Движок белых списков поддерживает только IPv4: DNS и разрешение имён этой сессии используют IPv4. Сохранённые настройки не изменены; явные IPv6-адреса этим движком не поддерживаются."
    private val ipv4Only = setOf("csqtt", "csqtt25", "qwdtt")
    const val POOL_NOTE="В пуле БС есть движки с IPv4-стеком: эта сессия разрешает имена в IPv4, чтобы горячая смена транспорта не требовала перезапуска TUN. Сохранённые настройки и IPv6-защита не изменены."
    fun forPool(profiles:List<JSONObject>,current:String?):String? =
        profiles.map(EngineModules::carrierKind).firstOrNull {it in ipv4Only}?:current

    fun apply(source: JSONObject, kind: String?): JSONObject {
        if (kind !in ipv4Only) return source
        return ipv4Only(source)
    }

    /** Explicit per-connection compatibility, also shared by IPv4-only carriers. */
    fun ipv4Only(source: JSONObject): JSONObject {
        val copy = JSONObject(source.toString())
        val dns = copy.optJSONObject("dns") ?: JSONObject().also { copy.put("dns", it) }
        narrow(dns)
        dns.put("strategy", "ipv4_only")
        // A resolve action can override the default DNS strategy; retain its position,
        // matches and server. Never insert a new action ahead of Direct/block guards.
        fun routeRules(rules: JSONArray?) {
            if (rules == null) return
            repeat(rules.length()) {
                val rule = rules.getJSONObject(it)
                if (rule.optString("action") == "resolve") {
                    narrow(rule)
                    rule.put("strategy", "ipv4_only")
                }
                routeRules(rule.optJSONArray("rules"))
            }
        }
        copy.optJSONObject("route")?.let { route ->
            routeRules(route.optJSONArray("rules"))
            route.optJSONObject("default_domain_resolver")?.let { resolver ->
                narrow(resolver); resolver.put("strategy", "ipv4_only")
            }
        }
        return copy
    }

    private fun narrow(value: Any) {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                if (key == "strategy" || key == "domain_strategy") {
                    val strategy = value.optString(key)
                    require(strategy !in setOf("ipv6_only", "prefer_ipv6")) {
                        "Для этой сессии выбран IPv4, а в DNS явно выбран IPv6. Выбери согласованную стратегию или отключи совместимость IPv4; настройки не изменены."
                    }
                    if (strategy in setOf("", "as_is", "prefer_ipv4", "ipv4_only")) value.put(key, "ipv4_only")
                } else narrow(value.get(key))
            }
            is JSONArray -> repeat(value.length()) { narrow(value.get(it)) }
        }
    }
}
