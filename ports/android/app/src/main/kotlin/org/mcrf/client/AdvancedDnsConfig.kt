package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Targets the installed libbox 1.14 API. No extra core is required. */
object AdvancedDnsConfig {
    fun defaults() = JSONObject().put("enabled", false).put("direct", JSONObject().put("provider","Системный").put("protocol","Системный"))
        .put("local", JSONObject().put("provider", "Системный").put("protocol", "Системный"))
        .put("final", "remote").put("remoteNoAAAA", false).put("directNoAAAA", false)
        .put("fakeIp", false).put("fakeNoAAAA", true).put("hosts", false).put("answers", "")
        .put("cache", true).put("capacity", 1024).put("optimistic", false).put("optimisticTimeout", "30s")
        .put("timeout", "10s").put("strategy", "prefer_ipv4").put("custom", "")

    fun value(state: JSONObject) = state.optJSONObject("advancedDns") ?: defaults()
    fun validate(s: JSONObject) {
        DnsConfig.build(s.getJSONObject("direct"), "d", "b")
        require(!DnsConfig.build(s.getJSONObject("local"), "l", "b").has("domain_resolver")) { "Локальный DNS должен использовать IP-адрес или системный резолвер." }
        require(s.getString("final") in setOf("remote", "direct")) { "Выбери DNS по умолчанию." }
        require(s.getString("strategy") in setOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")) { "Неизвестная стратегия DNS." }
        require(s.getInt("capacity") in 0..65536) { "Ёмкость кэша: 0–65536." }
        for (key in listOf("timeout", "optimisticTimeout")) require(s.getString(key).matches(Regex("[1-9][0-9]{0,4}(ms|s|m)"))) { "Время задаётся как 10s, 500ms или 1m." }
        require(!s.optBoolean("optimistic") || s.optBoolean("cache")) { "Оптимистичный кэш требует включённого кэша." }
        answers(s.optString("answers"))
        if (s.optString("custom").isNotBlank()) validateCustom(JSONObject(s.getString("custom")))
    }
    private fun validateCustom(dns: JSONObject) {
        require(dns.toString().length <= 262144) { "DNS-объект больше 256 КБ." }
        val servers = dns.getJSONArray("servers"); require(servers.length() in 1..64)
        val tags = (0 until servers.length()).map { servers.getJSONObject(it).getString("tag") }
        require(tags.distinct().size == tags.size && tags.all { it.isNotBlank() }) { "DNS-теги должны быть уникальны." }
        require(dns.optString("final") in tags) { "final должен ссылаться на DNS-сервер." }
        fun check(node: JSONObject) {
            if (node.has("server")) require(node.getString("server") in tags) { "Неизвестный DNS-сервер правила." }
            node.optJSONArray("rules")?.let { a -> repeat(a.length()) { check(a.getJSONObject(it)) } }
        }
        dns.optJSONArray("rules")?.let { a -> repeat(a.length()) { check(a.getJSONObject(it)) } }
    }
    fun answers(text: String): JSONObject {
        require(text.length <= 65536) { "Ответы hosts больше 64 КБ." }
        val result = JSONObject()
        text.lines().forEach { raw ->
            val parts = raw.substringBefore('#').trim().split(Regex("\\s+"))
            if (parts.first().isBlank()) return@forEach
            require(parts.size >= 2 && RouteRules.literal(parts[0]) != null) { "Ответ: IP домен [домен…]." }
            parts.drop(1).forEach { domain ->
                val parsed = RouteRules.parse("DOMAIN,$domain")
                require(parsed.getValue("domain").size == 1) { "Некорректный домен hosts." }
                val name = parsed.getValue("domain").first()
                val ips = result.optJSONArray(name) ?: JSONArray().also { result.put(name, it) }; ips.put(parts[0])
            }
        }
        return result
    }
    fun append(rules: JSONArray, match: JSONObject, server: String, noAAAA: Boolean) {
        if (noAAAA) rules.put(JSONObject(match.toString()).put("query_type", JSONArray().put("AAAA")).put("action", "predefined").put("rcode", "NOERROR"))
        rules.put(JSONObject(match.toString()).put("action", "route").put("server", server))
    }
    private fun domains(r: JSONObject): JSONObject? {
        if (r.optString("type") == "logical") {
            val children = r.getJSONArray("rules"); val safe = JSONArray()
            repeat(children.length()) { domains(children.getJSONObject(it))?.let(safe::put) }
            if (safe.length() == 0) return null
            return if (safe.length() == 1) safe.getJSONObject(0) else JSONObject().put("type", "logical").put("mode", r.getString("mode")).put("rules", safe)
        }
        val safe = JSONObject(); for (key in listOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "rule_set")) if (r.has(key)) safe.put(key, r.get(key))
        return safe.takeIf { it.length() > 0 }
    }
    /** Call after ordinary per-list DNS rules have been generated. Explicit list DNS wins. */
    private fun referencesIpRuleSet(match:JSONObject,ipTags:Set<String>):Boolean {
        when(val tags=match.opt("rule_set")) {
            is String -> if(tags in ipTags)return true
            is JSONArray -> if((0 until tags.length()).any {tags.optString(it) in ipTags})return true
        }
        val children=match.optJSONArray("rules")?:return false
        return (0 until children.length()).any {children.optJSONObject(it)?.let {child->referencesIpRuleSet(child,ipTags)}==true}
    }
    fun apply(config: JSONObject, state: JSONObject, proxy: String, direct: String, tun: Boolean, ipRuleSetTags:Set<String> = emptySet(),directIpv4Only:Boolean=false,implicitBootstrapTags:Set<String> = emptySet()) {
        val appOwned=DnsConfig.appOwned(config)
        val s = DirectDnsFamily.settings(state,appOwned,directIpv4Only); if (!s.optBoolean("enabled")) return
        validate(s)
        if (s.optString("custom").isNotBlank()) { config.put("dns", JSONObject(s.getString("custom"))); return }
        val servers = JSONArray()
        val local = DnsConfig.build(s.getJSONObject("local"), "mcrf-dns-local", "mcrf-dns-local")
        require(!local.has("domain_resolver")) { "Локальный DNS должен иметь IP-адрес, чтобы избежать цикла разрешения." }
        servers.put(local)
        servers.put(DnsConfig.applyDetour(DnsConfig.build(state.getJSONObject("globalDns"), "mcrf-dns-remote", "mcrf-dns-local"),config,proxy))
        servers.put(DnsConfig.applyDetour(DnsConfig.build(s.getJSONObject("direct"), "mcrf-dns-direct", "mcrf-dns-local"),config,direct))
        val rules = JSONArray()
        val mapping = answers(s.optString("answers"))
        if (s.optBoolean("hosts") || mapping.length() > 0) {
            servers.put(JSONObject().put("type", "hosts").put("tag", "mcrf-dns-hosts").put("predefined", mapping).apply { if (s.optBoolean("hosts")) put("path", JSONArray().put("/system/etc/hosts")) })
            rules.put(JSONObject().put("preferred_by", JSONArray().put("mcrf-dns-hosts")).put("query_type", JSONArray().put("A").put("AAAA")).put("action", "route").put("server", "mcrf-dns-hosts"))
        }
        // Keep explicit DNS-list servers and rules; their bootstrap references are retained.
        config.optJSONObject("dns")?.let { old ->
            old.optJSONArray("servers")?.let { a -> repeat(a.length()) { val server=a.getJSONObject(it);if(appOwned&&server.optString("tag")==DnsConfig.APP_DNS_TAG)return@repeat;require(!server.optString("tag").startsWith("mcrf-dns-")){"DNS-тег mcrf-dns-* занят профилем."};servers.put(JSONObject(server.toString())) } }
            old.optJSONArray("rules")?.let { a -> repeat(a.length()) { val r=a.getJSONObject(it);if(r.optString("server").startsWith("dns-list-"))rules.put(JSONObject(r.toString())) } }
        }
        // TUN DNS queries carry the original socket owner when Android can
        // attribute it. A pure app rule must select the same DNS path as its
        // traffic rule, not silently fall through to the domain/default path.
        // Unknown/system-resolver owners do not match these conditions.
        state.optJSONArray("vpnApps")?.takeIf {it.length()>0}?.let {apps->
            append(rules,JSONObject().put("package_name",JSONArray(apps.toString())),"mcrf-dns-remote",s.optBoolean("remoteNoAAAA"))
        }
        var countryDns:JSONObject?=null
        val ruleResolvers=linkedMapOf<String,String>()
        fun remoteFor(rule:JSONObject):String {
            if(RouteServerPlan.ids(rule).isEmpty())return "mcrf-dns-remote"
            RouteServerPlan.validate(rule)
            val outbound=RouteServerPlan.outbound(rule,"")
            val id=outbound.removePrefix("mcrf-route-")
            return ruleResolvers.getOrPut(id) {
                val tag="mcrf-dns-route-$id"
                servers.put(DnsConfig.applyDetour(DnsConfig.build(state.getJSONObject("globalDns"),tag,"mcrf-dns-local"),config,outbound))
                tag
            }
        }
        val selectors=GeoDataConfig.value(state).getJSONArray("selectors")
        val ipTags=ipRuleSetTags+(0 until selectors.length()).filter {selectors.getJSONObject(it).optString("kind")=="geoip"}.map {"mcrf-geo-$it"}
        GeoDataConfig.orderedRules(state).let { a -> repeat(a.length()) {
            val r = a.getJSONObject(it)
            if (!r.optBoolean("enabled", true) || r.has("dns")) return@repeat
            if(r.optJSONObject("nativeMatch")?.let {!RemoteRoutingImports.dnsSafe(it)}==true)return@repeat
            val compiledMatches=RouteRules.matches(r)
            if((0 until compiledMatches.length()).any {referencesIpRuleSet(compiledMatches.getJSONObject(it),ipTags)})return@repeat
            val groups = RouteRules.parse(r.getString("text"))
            if (groups.getValue("package_name").isNotEmpty()) {
                if(r.optString("mode","any") in setOf("apps","any")) {
                    val appMatch=JSONObject().put("package_name",JSONArray(groups.getValue("package_name")))
                    val target=r.optString("target","proxy")
                    if(target=="block")rules.put(appMatch.put("action","predefined").put("rcode","NXDOMAIN"))
                    else append(rules,appMatch,if(target=="direct")"mcrf-dns-direct" else remoteFor(r),s.optBoolean(if(target=="direct")"directNoAAAA" else "remoteNoAAAA"))
                }
                // Conditional app+address rules cannot discard their IP part
                // before resolution. Do not broaden them to all app DNS.
                if(r.optString("mode","any")!="any")return@repeat
            }
            // !(domain OR IP) cannot be projected to !domain before DNS has
            // produced the destination IP. Preserve the actual routing rule,
            // but leave its DNS choice to later rules/defaults. The country
            // preset intentionally derives its positive domain DNS separately.
            if (r.optBoolean("invert") && groups.getValue("ip_cidr").isNotEmpty() &&
                r.optString("builtinPreset") != "outside-ru") return@repeat
            val target = r.optString("target", "proxy"); val matches = compiledMatches
            repeat(matches.length()) { j ->
                val match = if(r.has("nativeMatch"))JSONObject(matches.getJSONObject(j).toString())else domains(matches.getJSONObject(j)) ?: return@repeat
                if(r.optString("builtinPreset")=="outside-ru") {
                    countryDns=match
                    return@repeat
                }
                if(r.optBoolean("invert"))match.put("invert",true)
                if (target == "block") rules.put(match.put("action", "predefined").put("rcode", "NXDOMAIN"))
                else append(rules, match, if (target == "direct") "mcrf-dns-direct" else remoteFor(r), s.optBoolean(if (target == "direct") "directNoAAAA" else "remoteNoAAAA"))
            }
        } }
        GeoDataConfig.value(state).getJSONArray("selectors").let {a -> repeat(a.length()){i -> val entry=a.getJSONObject(i)
            if(entry.getString("kind")=="geosite"&&!entry.optBoolean("bound")&&!entry.optBoolean("managedRule")){
                val match=JSONObject().put("rule_set",JSONArray().put("mcrf-geo-$i"));val target=entry.getString("target")
                if(target=="block")rules.put(match.put("action","predefined").put("rcode","NXDOMAIN"))
                else append(rules,match,if(target=="direct")"mcrf-dns-direct" else "mcrf-dns-remote",s.optBoolean(if(target=="direct")"directNoAAAA" else "remoteNoAAAA"))
            }
        }}
        countryDns?.let {append(rules,it,"mcrf-dns-direct",s.optBoolean("directNoAAAA"))}
        if (s.optBoolean("fakeIp")) {
            require(tun) { "FakeIP требует системного VPN." }
            servers.put(JSONObject().put("type", "fakeip").put("tag", "mcrf-dns-fake").put("inet4_range", "198.18.0.0/15").apply { if (!s.optBoolean("fakeNoAAAA")) put("inet6_range", "2001:db8::/32") })
            rules.put(JSONObject().put("query_type", JSONArray().put("A").put("AAAA")).put("action", "route").put("server", "mcrf-dns-fake"))
        }
        val finalDirect = s.getString("final") == "direct"
        append(rules, JSONObject(), if (finalDirect) "mcrf-dns-direct" else "mcrf-dns-remote", s.optBoolean(if (finalDirect) "directNoAAAA" else "remoteNoAAAA"))
        config.put("dns", JSONObject().put("servers", servers).put("rules", rules).put("final", if (finalDirect) "mcrf-dns-direct" else "mcrf-dns-remote")
            .put("strategy", s.getString("strategy").ifEmpty { "prefer_ipv4" }).put("disable_cache", !s.optBoolean("cache")).put("cache_capacity", s.getInt("capacity"))
            .put("timeout", s.getString("timeout")).put("independent_cache", true).apply { if (s.optBoolean("optimistic")) put("optimistic", JSONObject().put("enabled", true).put("timeout", s.getString("optimisticTimeout"))) })
        val route=config.getJSONObject("route")
        route.put("default_domain_resolver", JSONObject().put("server","mcrf-dns-direct").apply {if(s.getString("strategy").isNotEmpty())put("strategy",s.getString("strategy"))})
        ServerBootstrapDns.apply(config,"mcrf-dns-local",s.getString("strategy"),if(appOwned)implicitBootstrapTags else emptySet())
        if(s.getString("strategy").isNotEmpty()) {
            val old=route.getJSONArray("rules");val resolved=JSONArray();var added=false
            repeat(old.length()) {i -> val r=old.getJSONObject(i)
                if(!added && r.optString("action") !in setOf("sniff","hijack-dns") && !r.optString("outbound").startsWith("mcrf-loopback")) {resolved.put(JSONObject().put("action","resolve").put("strategy",s.getString("strategy")));added=true};resolved.put(r)
            }
            if(!added)resolved.put(JSONObject().put("action","resolve").put("strategy",s.getString("strategy")))
            route.put("rules",resolved)
        }
    }
}
