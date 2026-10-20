package org.mcrf.client

import org.json.JSONObject

/** Structural counters only: no tags, addresses, domains, app IDs or config values. */
object ConnectionDiagnostics {
    fun summary(config:JSONObject):String {
        val types=mutableMapOf<String,String>()
        for(key in listOf("outbounds","endpoints")){
            val outputs=config.optJSONArray(key)
            repeat(outputs?.length()?:0){i->outputs!!.optJSONObject(i)?.let {types[it.optString("tag")]=it.optString("type")}}
        }
        var direct=0;var via=0;var blocked=0;var special=0;var resolves=0
        val route=config.optJSONObject("route");val rules=route?.optJSONArray("rules")
        repeat(rules?.length()?:0){i->val rule=rules!!.optJSONObject(i)?:return@repeat
            when(rule.optString("action")){
                "resolve"->{resolves++;special++}
                "reject"->blocked++
                "route",""->when {
                    rule.optString("outbound").startsWith("mcrf-loopback")->special++
                    !rule.has("outbound")->special++
                    types[rule.optString("outbound")]=="direct"->direct++
                    types[rule.optString("outbound")]=="block"->blocked++
                    else->via++
                }
                else->special++
            }
        }
        val dns=config.optJSONObject("dns")
        val servers=dns?.optJSONArray("servers")
        var detours=0
        repeat(servers?.length()?:0){i->if(servers!!.optJSONObject(i)?.has("detour")==true)detours++}
        val final=when(types[route?.optString("final").orEmpty()]){"direct"->"напрямую";null->"иной/неизвестен";else->"через движок"}
        val dnsFinal=(0 until (servers?.length()?:0)).mapNotNull {servers!!.optJSONObject(it)}.firstOrNull {it.optString("tag")==dns?.optString("final")}
        val dnsType=dnsFinal?.optString("type")?.takeIf {it in setOf("local","udp","tcp","tls","quic","https","h3","hosts","fakeip") }?:"иной/неизвестен"
        return "Маршруты: напрямую=$direct, через движок=$via, блок=$blocked, служебные=$special; выход по умолчанию=$final; resolve=$resolves. DNS: серверов=${servers?.length()?:0}, правил=${dns?.optJSONArray("rules")?.length()?:0}, явных detour=$detours, по умолчанию=$dnsType. Счётчики конфигурации, не проверка трафика."
    }
}
