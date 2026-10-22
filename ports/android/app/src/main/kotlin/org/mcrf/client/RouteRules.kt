package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.Locale

/** Ordered Windows list semantics; Android package selection replaces process paths. */
object RouteRules {
    private val textCache=RouteTextCache()
    private val whitespace=Regex("[ \\t]+")
    private val domainPrefix=Regex("^[*.]+")
    private val domainTail=Regex("\\^+$")
    private val domainName=Regex("(?=.{1,253}$)[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)*")
    private val ipv4Literal=Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")
    private val ipv6Literal=Regex("[0-9a-fA-F:.]+")
    private val addressKeys = arrayOf("domain", "domain_suffix", "ip_cidr")
    private val appKeys = arrayOf("package_name", "process_name", "process_path")
    @JvmStatic fun parse(text: String): Map<String, List<String>> {
        return textCache.parse(text,::parseUncached)
    }
    private fun parseUncached(text:String):Map<String,List<String>> {
        require(text.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES) { "Список больше 4 МБ." }
        val groups = (addressKeys + appKeys).associateWithTo(linkedMapOf()) { linkedSetOf<String>() }
        for (raw in text.lines()) {
            var line = raw.substringBefore('#').trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith('!') || line.startsWith('[')) continue
            var domainKind = "domain_suffix"
            if (line.contains(',')) {
                val columns = line.split(','); val kind = columns[0].trim().uppercase(Locale.ROOT)
                require(columns.size in 2..3 && (columns.size != 3 || columns[2].trim().equals("no-resolve", true))) { "Некорректная запись списка." }
                require(kind in setOf("DOMAIN", "DOMAIN-SUFFIX", "IP-CIDR", "IP-CIDR6")) { "Неподдерживаемый тип записи списка." }
                line = columns[1].trim(); if (kind == "DOMAIN") domainKind = "domain"
            }
            if (line.startsWith("app:", true) || line.startsWith("pkg:", true)) {
                val value = line.substring(4).trim(); validatePackage(value); groups.getValue("package_name").add(value); continue
            }
            require(!line.startsWith("path:", true)) { "На Android вместо путей процессов выбери приложение." }
            val parts = line.split(whitespace)
            val candidates = if (parts.size > 1 && literal(parts[0]) != null) parts.drop(1) else listOf(line)
            for (candidate in candidates) {
                var value = candidate.trim()
                if (value in setOf("localhost", "localhost.localdomain", "broadcasthost") || value.startsWith("ip6-") || value.endsWith(".local")) continue
                val address = literal(value)
                if (address != null) {
                    if (!address.isLoopbackAddress && !address.isAnyLocalAddress) groups.getValue("ip_cidr").add(value.lowercase(Locale.ROOT) + if (value.contains(':')) "/128" else "/32")
                    continue
                }
                if (value.contains('/')) {
                    val cidr = value.split('/'); val bits = if (cidr.size == 2) cidr[1].toIntOrNull() ?: -1 else -1
                    require(cidr.size == 2 && literal(cidr[0]) != null && bits in 0..if (cidr[0].contains(':')) 128 else 32) { "Некорректная IP-подсеть." }
                    groups.getValue("ip_cidr").add(value.lowercase(Locale.ROOT)); continue
                }
                value = value.replaceFirst(domainPrefix, "")
                if (value.startsWith("||")) value = value.substring(2).replaceFirst(domainTail, "")
                require(value.matches(domainName)) { "Некорректный домен списка." }
                groups.getValue(domainKind).add(value.lowercase(Locale.ROOT))
            }
        }
        return groups.mapValues { it.value.toList() }
    }
    @JvmStatic fun literal(value: String): InetAddress? {
        if (value.contains('%') || !value.matches(if (value.contains(':')) ipv6Literal else ipv4Literal)) return null
        if (!value.contains(':') && value.split('.').any { it.toInt() > 255 }) return null
        return try { InetAddress.getByName(value) } catch (_: Exception) { null }
    }
    @JvmStatic fun validatePackage(value: String) { require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))) { "Укажи пакет Android как app:org.example.client." } }
    private fun any(groups: Map<String, List<String>>, keys: Array<String>) = keys.any { groups.getValue(it).isNotEmpty() }
    private fun matchAny(groups: Map<String, List<String>>, keys: Array<String>): JSONObject {
        val children = JSONArray(); keys.filter { groups.getValue(it).isNotEmpty() }.forEach { children.put(JSONObject().put(it, JSONArray(groups.getValue(it)))) }
        return if (children.length() == 1) children.getJSONObject(0) else JSONObject().put("type", "logical").put("mode", "or").put("rules", children)
    }
    private fun listDnsMatch(rule:JSONObject,groups:Map<String,List<String>>,ipPresetTags:Set<String>):JSONObject? {
        val ids=PresetRules.ids(rule)
        val domainPresets=ids.filter {PresetRules.tag(it) !in ipPresetTags}
        val invert=rule.optBoolean("invert",false)
        if(invert&&(groups.getValue("ip_cidr").isNotEmpty()||domainPresets.size!=ids.size))return null
        val parts=JSONArray()
        if(any(groups,arrayOf("domain","domain_suffix")))parts.put(matchAny(groups,arrayOf("domain","domain_suffix")))
        if(domainPresets.isNotEmpty())parts.put(PresetRules.match(domainPresets))
        if(parts.length()==0)return if(!invert&&groups.getValue("ip_cidr").containsAll(listOf("0.0.0.0/0","::/0")))JSONObject()else null
        val match=if(parts.length()==1)parts.getJSONObject(0)else JSONObject().put("type","logical").put("mode","or").put("rules",parts)
        if(invert)match.put("invert",true)
        return match
    }
    @JvmStatic fun matches(rule: JSONObject): JSONArray {
        rule.optJSONObject("nativeMatch")?.let { match ->
            require(rule.optString("text").isBlank()&&!rule.has("presetIds")&&!rule.optBoolean("invert")&&!rule.has("dns")){"Импортированное точное условие нельзя смешивать с обычным редактором списка."}
            RemoteRoutingImports.validateMatch(match)
            return JSONArray().put(JSONObject(match.toString()))
        }
        val presets = PresetRules.ids(rule)
        val groups = parse(rule.getString("text")); val addresses = any(groups, addressKeys) || presets.isNotEmpty(); val apps = any(groups, appKeys)
        fun addressMatch(): JSONObject {
            if (presets.isEmpty()) return matchAny(groups, addressKeys)
            if (!any(groups,addressKeys)) return PresetRules.match(presets)
            return JSONObject().put("type","logical").put("mode","or").put("rules",JSONArray().put(matchAny(groups,addressKeys)).put(PresetRules.match(presets)))
        }
        val mode = rule.optString("mode", "any"); val target = rule.optString("target", "proxy"); var result = JSONArray()
        when (mode) {
            "in-apps", "except-apps" -> {
                require(addresses && apps) { "Для этого условия нужны и приложения, и домены/IP." }
                val application = matchAny(groups, appKeys); if (mode == "except-apps") application.put("invert", true)
                result.put(JSONObject().put("type", "logical").put("mode", "and").put("rules", JSONArray().put(addressMatch()).put(application)))
            }
            "apps" -> { require(apps) { "В списке нет приложений." }; result.put(matchAny(groups, appKeys)) }
            "addresses" -> { require(addresses) { "В списке нет доменов/IP." }; result.put(addressMatch()) }
            "any" -> { if (addresses) result.put(addressMatch()); if (apps) result.put(matchAny(groups, appKeys)) }
            else -> throw IllegalArgumentException("Неизвестное условие маршрута.")
        }
        require(result.length() > 0) { "Список маршрута пуст." }
        if (rule.optBoolean("invert", false)) {
            require(mode == "addresses" && !apps && target == "proxy") { "Инверсия адресов доступна только для VPN и условия «Домены/IP»." }
            result = JSONArray().put(addressMatch().put("invert", true))
        }
        require(rule.optJSONObject("dns") == null || !(apps || mode in setOf("apps", "in-apps", "except-apps"))) { "Отдельный DNS списка доступен для доменных правил без условия по приложениям." }
        return result
    }
    @JvmStatic fun defaults(): JSONObject = JSONObject().put("version", 1).put("defaultTarget", "proxy").put("globalDns", DnsConfig.defaults())
        .put("excludedApps", JSONArray()).put("vpnApps", JSONArray()).put("rules", JSONArray())
    @JvmStatic fun outsideRussia(text: String): JSONObject = JSONObject().put("id", "outside-ru").put("name", "Всё кроме РФ")
        .put("text", text).put("mode", "addresses").put("target", "proxy").put("invert", true).put("enabled", true).put("builtinPreset", "outside-ru")
    /** The default catch-all remains after newly added specific lists. */
    fun insert(settings: JSONObject, rule: JSONObject) {
        val previous=settings.getJSONArray("rules");val values=JSONArray();var inserted=false
        repeat(previous.length()) { index -> val old=previous.getJSONObject(index)
            if(!inserted&&old.optString("builtinPreset")=="outside-ru"){values.put(rule);inserted=true};values.put(old)
        }
        if(!inserted)values.put(rule);settings.put("rules",values)
    }
    @JvmStatic @JvmOverloads fun apply(source: JSONObject, settings: JSONObject, tun: Boolean, presetPaths: Map<String, String> = emptyMap(),ipPresetTags:Set<String> = emptySet(),directIpv4Only:Boolean=false,routeServers:List<ServerImport.Server> = emptyList()): JSONObject {
        val config = RouteServerPlan(settings,routeServers).append(source); val route = config.getJSONObject("route"); val proxy = route.getString("final")
        val appDns=DnsConfig.appOwned(config)
        PresetRules.attach(config,settings,presetPaths)
        val outputs = config.getJSONArray("outbounds")
        var direct = (0 until outputs.length()).map { outputs.getJSONObject(it) }.firstOrNull { it.optString("type") == "direct" && !it.optString("tag").startsWith("mcrf-loopback") }?.getString("tag")
        if (direct == null) {
            direct = "mcrf-direct"; val tags = hashSetOf<String>()
            listOf("outbounds", "endpoints").forEach { key -> config.optJSONArray(key)?.let { list -> repeat(list.length()) { tags.add(list.getJSONObject(it).getString("tag")) } } }
            while (direct in tags) direct += "-1"
            outputs.put(JSONObject().put("type", "direct").put("tag", direct))
        }
        val directTag = direct
        val original = route.optJSONArray("rules") ?: JSONArray(); val ordered = JSONArray(); var sniff = false; var dnsHijack = false
        repeat(original.length()) { i -> val item = original.getJSONObject(i)
            if (essential(item)) { ordered.put(JSONObject(item.toString())); sniff = sniff || item.optString("action") == "sniff"; dnsHijack = dnsHijack || item.optString("action") == "hijack-dns" }
        }
        if (!sniff) ordered.put(JSONObject().put("action", "sniff"))
        if (!dnsHijack) ordered.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        settings.optJSONArray("vpnApps")?.takeIf { it.length() > 0 }?.let { forced ->
            require(tun) { "Приложения «только VPN» требуют системного VPN." }; repeat(forced.length()) { validatePackage(forced.getString(it)) }
            ordered.put(JSONObject().put("package_name", JSONArray(forced.toString())).put("action", "route").put("outbound", proxy))
        }
        var dns = config.optJSONObject("dns"); val dnsRules = JSONArray(); val rules = GeoDataConfig.orderedRules(settings)
        repeat(rules.length()) { i ->
            val rule = rules.getJSONObject(i); if (!rule.optBoolean("enabled", true)) return@repeat
            val groups = parse(rule.getString("text"))
            require(tun || (groups.getValue("package_name").isEmpty()&&rule.optJSONObject("nativeMatch")?.let {RemoteRoutingImports.hasAppConditions(it)}!=true)) { "Маршруты по приложениям работают только с системным VPN." }
            val target = rule.optString("target", "proxy"); val compiled = matches(rule)
            val ruleProxy=RouteServerPlan.outbound(rule,proxy)
            repeat(compiled.length()) { j -> val match = compiled.getJSONObject(j); match.put("action", if (target == "block") "reject" else "route")
                if(target=="block")for(key in listOf("reject_method","no_drop"))if(rule.has(key))match.put(key,rule.get(key))
                if (target != "block") match.put("outbound", target(target, ruleProxy, directTag)); ordered.put(match) }
            rule.optJSONObject("dns")?.takeIf { target != "block" }?.let { dnsSettings ->
                val dnsMatch=listDnsMatch(rule,groups,ipPresetTags)?:return@let
                val currentDns = dns ?: JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "bootstrap"))).put("final", "bootstrap").also { config.put("dns", it); dns = it }
                val servers = currentDns.getJSONArray("servers")
                val bootstrap = (0 until servers.length()).map { servers.getJSONObject(it) }.firstOrNull { it.optString("type") == "local" }?.getString("tag")
                    ?: uniqueTag(servers, "mcrf-bootstrap").also { servers.put(JSONObject().put("type", "local").put("tag", it)) }
                val tag = uniqueTag(servers, "dns-list-${rule.getString("id")}"); val server = DnsConfig.build(dnsSettings, tag, bootstrap)
                if (target == "proxy") DnsConfig.applyDetour(server,config,ruleProxy)
                servers.put(server)
                dnsRules.put(dnsMatch.put("action","route").put("server",tag))
            }
        }
        repeat(original.length()) { if (!essential(original.getJSONObject(it))) ordered.put(JSONObject(original.getJSONObject(it).toString())) }
        val defaultTarget = settings.optString("defaultTarget", "proxy")
        if (defaultTarget == "block") { ordered.put(JSONObject().put("action", "reject")); route.put("final", directTag) } else route.put("final", target(defaultTarget, proxy, directTag))
        route.put("rules", ordered)
        if (dnsRules.length() > 0) { dns?.optJSONArray("rules")?.let { old -> repeat(old.length()) { dnsRules.put(JSONObject(old.getJSONObject(it).toString())) } }; dns!!.put("rules", dnsRules) }
        val excluded = settings.getJSONArray("excludedApps")
        if (excluded.length() > 0) {
            require(tun) { "Исключения приложений работают только с системным VPN." }; repeat(excluded.length()) { validatePackage(excluded.getString(it)) }
            val inbounds = config.getJSONArray("inbounds"); repeat(inbounds.length()) { val inbound = inbounds.getJSONObject(it); if (inbound.getString("type") == "tun") inbound.put("exclude_package", JSONArray(excluded.toString())) }
        }
        AdvancedDnsConfig.apply(config, settings, proxy, directTag, tun, ipPresetTags,directIpv4Only,ServerBootstrapDns.implicitTags(routeServers))
        GeoDataConfig.attach(config, settings, presetPaths, proxy, directTag)
        RemoteRoutingConfig.apply(config, settings, proxy, directTag)
        RoutingDefaults.applyAppMode(config, settings, tun)
        // Resolve once at the actual first destination-IP condition, after Geo
        // lists have been inserted. A rule_set can contain IPs without a literal
        // ip_cidr key; domain-only lists must keep their earlier DNS-free priority.
        val geoSelectors=GeoDataConfig.value(settings).getJSONArray("selectors")
        val ipSets=ipPresetTags+(0 until geoSelectors.length()).filter {geoSelectors.getJSONObject(it).optString("kind")=="geoip"}.map {"mcrf-geo-$it"}
        val finalRules=route.getJSONArray("rules");val resolvedRules=JSONArray();var resolved=false
        repeat(finalRules.length()){i->val rule=finalRules.getJSONObject(i)
            // Advanced DNS may already have inserted an unconditional resolve
            // with an explicit address-family strategy. Do not resolve twice;
            // a conditional resolve must not suppress the later IP gate.
            if(rule.optString("action")=="resolve"&&rule.keys().asSequence().all {it in setOf("action","strategy")})resolved=true
            // The pre-existing loopback guard protects local listeners and must
            // not force DNS ahead of all user/domain/app rules.
            if(!resolved&&!essential(rule)&&destinationIpMatch(rule,ipSets)){resolvedRules.put(JSONObject().put("action","resolve"));resolved=true}
            resolvedRules.put(rule)
        }
        route.put("rules",resolvedRules)
        if(directIpv4Only&&appDns)DirectDnsFamily.earlyReject(config,settings)?.let {guard->
            val guarded=JSONArray();var added=false
            repeat(resolvedRules.length()){i->val rule=resolvedRules.getJSONObject(i)
                if(!added&&rule.optString("action")=="sniff"){guarded.put(guard);added=true};guarded.put(rule)
            }
            if(added)route.put("rules",guarded)
        }
        return config
    }
    private fun essential(item: JSONObject) = item.optString("action", "") in setOf("sniff", "hijack-dns") || item.optString("outbound", "").startsWith("mcrf-loopback")
    private fun destinationIpMatch(match:JSONObject,ipSets:Set<String>):Boolean {
        if(match.has("ip_cidr")||match.has("ip_is_private"))return true
        match.optJSONArray("rule_set")?.let {tags->if((0 until tags.length()).any {tags.optString(it) in ipSets})return true}
        if(match.opt("rule_set") is String&&match.getString("rule_set") in ipSets)return true
        val children=match.optJSONArray("rules")?:return false
        return (0 until children.length()).any {children.optJSONObject(it)?.let {child->destinationIpMatch(child,ipSets)}==true}
    }
    private fun target(target: String, proxy: String, direct: String): String = when (target) { "proxy" -> proxy; "direct" -> direct; else -> throw IllegalArgumentException("Неизвестный выход маршрута.") }
    private fun uniqueTag(servers: JSONArray, prefix: String): String {
        val tags = (0 until servers.length()).mapTo(hashSetOf()) { servers.getJSONObject(it).getString("tag") }; var tag = prefix
        while (tag in tags) tag += "-1"; return tag
    }
}
