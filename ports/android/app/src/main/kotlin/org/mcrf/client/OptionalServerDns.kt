package org.mcrf.client

import org.json.JSONObject

/** Server-address DNS only. Never copies website DNS rules or silently falls
 * back to the platform resolver. A private worker has no parent DNS tag table. */
internal object OptionalServerDns {
    private fun only(value:JSONObject,keys:Set<String>) {
        require(value.keys().asSequence().all {it in keys}) {
            "Выбранные параметры DNS адреса сервера пока нельзя точно передать этому движку."
        }
    }
    fun capture(config:JSONObject,out:JSONObject):JSONObject? {
        val raw=out.opt("domain_resolver")?:config.optJSONObject("route")?.opt("default_domain_resolver")?:return null
        val binding=when(raw){is String->JSONObject().put("server",raw);is JSONObject->JSONObject(raw.toString());else->throw IllegalArgumentException("Некорректный DNS адреса сервера.")}
        only(binding,setOf("server","strategy"))
        val strategy=binding.optString("strategy")
        require(strategy in setOf("","prefer_ipv4","prefer_ipv6","ipv4_only","ipv6_only"))
        val tag=binding.getString("server")
        val servers=config.optJSONObject("dns")?.optJSONArray("servers")
        val matches=(0 until (servers?.length()?:0)).map {servers!!.getJSONObject(it)}.filter {it.optString("tag")==tag}
        require(matches.size==1){"Не найден единственный DNS адреса сервера: настройки не заменены системными."}
        val endpoint=JSONObject(matches.single().toString())
        only(endpoint,setOf("type","tag","server","server_port","path","tls","detour"))
        val type=endpoint.getString("type")
        require(type in setOf("local","udp","tcp","tls","https")){"Этот протокол DNS адреса сервера пока не поддержан внешним движком."}
        if(endpoint.has("detour")) {
            val outputs=config.optJSONArray("outbounds")
            val direct=(0 until (outputs?.length()?:0)).map {outputs!!.getJSONObject(it)}.singleOrNull {it.optString("tag")==endpoint.getString("detour")}
            require(direct!=null&&direct.optString("type")=="direct"&&direct.keys().asSequence().all {it in setOf("tag","type")}){
                "DNS адреса сервера через другой выход нельзя заменить прямым DNS."
            }
        }
        if(type=="local")require(endpoint.keys().asSequence().all {it in setOf("tag","type")})
        else {
            require(RouteRules.literal(endpoint.getString("server"))!=null){"DNS адреса сервера требует отдельного bootstrap: системный DNS не подставлен."}
            require(endpoint.optInt("server_port",when(type){"tls"->853;"https"->443;else->53}) in 1..65535)
            if(type in setOf("tls","https"))endpoint.optJSONObject("tls")?.let {tls->
                only(tls,setOf("enabled","server_name"));require(tls.optBoolean("enabled",true))
            } else require(!endpoint.has("tls")&&!endpoint.has("path"))
            if(type=="tls")require(!endpoint.has("path"))
        }
        endpoint.remove("tag");endpoint.remove("detour")
        return JSONObject().put("endpoint",endpoint).put("strategy",strategy)
    }
}
