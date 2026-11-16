package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Runtime DNS policy only. No catalogue/settings migration and no TUN address changes. */
object DirectDnsFamily {
    fun requiresRestart(applied:Boolean?,current:Boolean):Boolean=applied!=null&&applied!=current
    /** Only an unambiguous first app rule or built-in country policy decides Direct before sniffing.
     * The native TUN pre-match rejects before its synthetic TCP accept, allowing
     * Happy Eyeballs to try cached IPv4. Never pre-empt arbitrary domain/app policies. */
    fun earlyReject(config:JSONObject,state:JSONObject):JSONObject? {
        val route=config.getJSONObject("route")
        val outputs=config.getJSONArray("outbounds")
        val directTags=(0 until outputs.length()).map {outputs.getJSONObject(it)}.filter {it.optString("type")=="direct"}.map {it.optString("tag")}.toSet()
        val compiled=route.getJSONArray("rules")
        val meaningful=(0 until compiled.length()).map {compiled.getJSONObject(it)}.filter {
            it.optString("action") !in setOf("sniff","hijack-dns","resolve")&&!it.optString("outbound").startsWith("mcrf-loopback")
        }
        val first=meaningful.firstOrNull()
        if(first!=null&&first.optString("outbound") in directTags&&first.optString("action","route")=="route"&&
            first.optJSONArray("package_name")?.length()?.let {it>0}==true&&
            first.keys().asSequence().all {it in setOf("package_name","action","outbound")}) {
            return JSONObject().put("network","tcp").put("ip_version",6).put("ip_cidr",JSONArray().put("2000::/3"))
                .put("package_name",JSONArray(first.getJSONArray("package_name").toString())).put("action","reject").put("method","reply")
        }
        if(route.optString("final") !in directTags)return null
        val rules=state.getJSONArray("rules")
        val active=(0 until rules.length()).map {rules.getJSONObject(it)}.filter {it.optBoolean("enabled",true)}
        val country=active.singleOrNull()?:return null
        if(country.optString("builtinPreset")!="outside-ru"||country.optString("mode")!="addresses"||
            !country.optBoolean("invert")||country.optString("target")!="proxy"||country.has("nativeMatch")||country.has("presetIds"))return null
        if(meaningful.size!=1||!meaningful.single().optBoolean("invert"))return null
        val actual=JSONObject(meaningful.single().toString()).apply {remove("action");remove("outbound")}
        if(!RoutingDefaultsEdits.equivalent(actual,RouteRules.matches(country).getJSONObject(0)))return null
        val globalV6=RouteRules.parse(country.getString("text")).getValue("ip_cidr").filter {cidr->
            val ip=RouteRules.literal(cidr.substringBefore('/'))
            ip!=null&&ip.address.size==16&&cidr.substringAfter('/').toInt()>=3&&(ip.address[0].toInt() and 0xe0)==0x20
        }
        if(globalV6.isEmpty())return null
        return JSONObject().put("network","tcp").put("ip_version",6).put("ip_cidr",JSONArray(globalV6))
            .put("action","reject").put("method","reply")
    }
    fun settings(state:JSONObject,appOwned:Boolean,directIpv4Only:Boolean):JSONObject {
        val saved=AdvancedDnsConfig.value(state)
        // The removed opt-in must not make the visible basic DNS controls fictitious.
        // Only visible basic fields become effective for app-generated DNS. Disabled
        // legacy FakeIP/hosts/custom JSON and other hidden extras stay disabled.
        val result=if(appOwned&&!saved.optBoolean("enabled"))AdvancedDnsConfig.defaults().apply {
            put("enabled",true).put("strategy","")
            val visible=JSONObject(saved.toString())
            for(key in listOf("direct","local","final"))if(visible.has(key))put(key,visible.get(key))
        }else JSONObject(saved.toString())
        // Physical reachability is NOT a DNS address-family policy. Android may
        // share cached answers between apps with different routes. Keep AAAA for
        // VPN-capable apps; earlyReject handles only provably Direct TCP flows.
        // Explicit user NoAAAA/global IPv4 compatibility remain separate opt-ins.
        return result
    }
}
