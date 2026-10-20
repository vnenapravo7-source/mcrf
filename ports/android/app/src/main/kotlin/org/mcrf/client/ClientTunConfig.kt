package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Applies after ProfileConfig + DNS/routing + LocalProxyConfig, before libbox.checkConfig/start. */
object ClientTunConfig {
    data class Result(val config: JSONObject, val localPort: Int?, val excludedCidrs: List<String>, val notes: List<String>)
    fun apply(source: JSONObject, settings: ClientTunSettings, auth: JSONObject, selectedPort: Int): Result {
        settings.validate(auth); require(selectedPort in 1024..65535)
        val config = JSONObject(source.toString()); val notes = mutableListOf<String>()
        val inputs = config.getJSONArray("inbounds"); val next = JSONArray()
        val route = config.optJSONObject("route") ?: JSONObject().also { config.put("route",it) }
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules",it) }
        val outputs = config.getJSONArray("outbounds")
        var direct = "mcrf-tun-direct"; val tags = (0 until outputs.length()).map { outputs.getJSONObject(it).optString("tag") }.toSet()
        while (direct in tags) direct += "-1"
        val needsDirect = settings.bypassPrivate || settings.bypassDirectIps
        if (needsDirect) outputs.put(JSONObject().put("type","direct").put("tag",direct))
        // Private fallback goes after all explicit server/user/DNS rules, preserving their priority.
        if (settings.bypassPrivate) rules.put(JSONObject().put("ip_cidr",JSONArray(ClientTunCidr.lines(settings.privateRanges).map { it.toString() })).put("action","route").put("outbound",direct))
        val exclusions = if (settings.bypassDirectIps) directExclusions(config, settings, notes) else emptyList()
        repeat(inputs.length()) { i -> val input = inputs.getJSONObject(i)
            when (input.optString("tag")) {
                "tun-in" -> {
                    input.put("mtu",settings.mtu).put("address",JSONArray().put(settings.address4).also { if (settings.ipv6) it.put(settings.address6) })
                    if (exclusions.isNotEmpty()) input.put("route_exclude_address",JSONArray(exclusions))
                    if (settings.httpProxy) input.put("platform",JSONObject().put("http_proxy",JSONObject().put("enabled",true).put("server","127.0.0.1").put("server_port",selectedPort).put("bypass_domain",JSONArray(settings.httpBypassLines()))))
                    next.put(input)
                }
                "local-proxy" -> if (settings.mixedEnabled) {
                    input.put("listen_port",selectedPort)
                    if (auth.getBoolean("enabled")) input.put("users", users(auth)) else input.remove("users")
                    next.put(input)
                }
                else -> next.put(input)
            }
        }
        if (settings.shareLan) next.put(JSONObject().put("type","mixed").put("tag","mcrf-lan").put("listen","0.0.0.0").put("listen_port",settings.lanPort).put("users",users(auth)))
        if (settings.dnsPort > 0) {
            next.put(JSONObject().put("type","direct").put("tag","mcrf-dns").put("listen","127.0.0.1").put("listen_port",settings.dnsPort))
            val dnsRules = JSONArray().put(JSONObject().put("inbound",JSONArray().put("mcrf-dns")).put("action","hijack-dns"))
            repeat(rules.length()) { dnsRules.put(rules.get(it)) }; route.put("rules",dnsRules)
        }
        settings.customListeners().forEach(next::put)
        validatePorts(next, config)
        config.put("inbounds",next)
        // Runtime policy, shared by normal VPN, BS carriers and local proxy starts.
        // Never rewrite the catalogue/profile or remove the IPv6 TUN/leak barrier.
        val compatible = if (settings.ipv4Compatibility) {
            notes.add("Общая совместимость IPv4: имена всех подключений разрешаются в IPv4; профили и IPv6-защита TUN не изменены.")
            CarrierAddressFamily.ipv4Only(config)
        } else config
        return Result(compatible, if (settings.mixedEnabled) selectedPort else null, exclusions, notes)
    }
    private fun users(auth: JSONObject) = JSONArray().put(JSONObject().put("username",auth.getString("username")).put("password",auth.getString("password")))
    fun validatePorts(inputs: JSONArray, config: JSONObject) {
        val used = hashSetOf<Int>()
        repeat(inputs.length()) { i -> val item = inputs.getJSONObject(i); if (item.has("listen_port")) require(used.add(item.getInt("listen_port"))) { "Порты listener не должны совпадать." } }
        val clash = config.optJSONObject("experimental")?.optJSONObject("clash_api")?.optString("external_controller")?.substringAfterLast(':')?.toIntOrNull()
        if (clash != null) require(used.add(clash)) { "Clash API конфликтует с listener." }
        config.optJSONArray("services")?.let { list -> repeat(list.length()) { i -> val port = list.getJSONObject(i).optInt("listen_port"); if (port > 0) require(used.add(port)) { "Порт API/service конфликтует с listener." } } }
    }
    /** Only unconditional, CIDR-only direct rules can safely leave TUN. All earlier CIDRs win.
     * A domain/package/ruleset/complex condition has unknown IP coverage: later bypass is kept inside TUN. */
    fun directExclusions(config: JSONObject, settings: ClientTunSettings, notes: MutableList<String> = mutableListOf()): List<String> {
        val directTags = mutableSetOf<String>()
        config.optJSONArray("outbounds")?.let { array -> repeat(array.length()) { i -> array.getJSONObject(i).let { if (it.optString("type") == "direct") directTags.add(it.optString("tag")) } } }
        var opaqueBefore = false; val protected = mutableListOf(ClientTunCidr.parse(settings.address4))
        if (settings.ipv6) protected.add(ClientTunCidr.parse(settings.address6))
        val excluded = mutableListOf<ClientTunCidr>()
        config.optJSONObject("route")?.optJSONArray("rules")?.let { rules -> repeat(rules.length()) { i ->
            val rule = rules.getJSONObject(i); val keys = rule.keys().asSequence().toSet()
            val cidrs = rule.optJSONArray("ip_cidr")?.let { array -> (0 until array.length()).map { ClientTunCidr.parse(array.getString(it)) } }
                ?: rule.optString("ip_cidr").takeIf { it.isNotBlank() }?.let { listOf(ClientTunCidr.parse(it)) }
            val simple = cidrs != null && keys.all { it in setOf("ip_cidr","action","outbound") } && rule.optString("action","route") == "route"
            if (simple && rule.optString("outbound") in directTags && !opaqueBefore) excluded.addAll(ClientTunCidr.excluding(cidrs!!,protected))
            else if (simple) protected.addAll(cidrs!!)
            else if (rule.optString("action") !in setOf("sniff","resolve","hijack-dns")) opaqueBefore = true
        } }
        if (opaqueBefore) notes.add("Условные маршруты сохранены внутри TUN: вывод IP наружу мог бы обойти более раннее правило.")
        return excluded.filter { settings.ipv6 || it.bits == 32 }.distinct().map { it.toString() }
    }
}
