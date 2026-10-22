package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** With multiple server paths, one transport's inner IPv4-only stack is NOT a
 * session-wide DNS policy. Narrow only DNS rules demonstrably routed through
 * that server. Never alter TUN IPv6, an unrelated rule, Direct or DNS answers
 * globally. Explicit global compatibility remains applied by ClientTunConfig. */
internal object RouteServerAddressFamily {
    fun apply(source:JSONObject,mainOutbound:String,mainIpv4:Boolean,additionalIpv4:Set<String>):JSONObject {
        if(!mainIpv4&&additionalIpv4.isEmpty())return source
        val config=JSONObject(source.toString());val dns=config.optJSONObject("dns")?:return config
        val restricted=additionalIpv4.mapTo(linkedSetOf()) {RouteServerPlan.tag(it)}
        if(mainIpv4)restricted.add(mainOutbound)
        val resolvers=linkedSetOf<String>()
        val servers=dns.optJSONArray("servers")?:JSONArray()
        repeat(servers.length()){i->val server=servers.getJSONObject(i)
            val tag=server.optString("tag")
            if(server.optString("detour") in restricted||mainIpv4&&tag=="mcrf-dns-remote"||
                additionalIpv4.any {tag=="mcrf-dns-route-$it"})resolvers.add(tag)
        }
        fun scoped(rule:JSONObject):Boolean {
            val children=rule.optJSONArray("rules")
            if(rule.optString("type")=="logical"||children!=null) {
                if(children==null||children.length()==0)return false
                val proofs=(0 until children.length()).map {index->
                    val child=children.optJSONObject(index)?:return false
                    scoped(child)
                }
                // OR is scoped only if EVERY branch is scoped. A query-type
                // branch otherwise turns this into a session-wide filter.
                return when(rule.optString("mode")) {
                    "and"->if(rule.optBoolean("invert"))proofs.all {it} else proofs.any {it}
                    "or"->proofs.all {it}
                    else->false
                }
            }
            return rule.keys().asSequence().any {key->
                key in setOf("domain","domain_suffix","domain_regex","domain_keyword","package_name","process_name","rule_set")&&
                    when(val value=rule.opt(key)) {is JSONArray->value.length()>0;is String->value.isNotBlank();else->false}
            }
        }
        fun narrowRules(rules:JSONArray?) {
            if(rules==null)return
            repeat(rules.length()){i->val rule=rules.getJSONObject(i)
                // A catch-all DNS action cannot prove the eventual outbound of
                // an IP-conditioned route. Preserve both families in that case.
                if(scoped(rule)&&rule.optString("action","route")=="route"&&rule.optString("server") in resolvers) {
                    val strategy=rule.optString("strategy",dns.optString("strategy"))
                    require(strategy !in setOf("ipv6_only","prefer_ipv6")) {"Для сервера этого правила нужен IPv4, но для его DNS явно выбран IPv6. Остальные серверы и сохранённые настройки не изменены."}
                    rule.put("strategy","ipv4_only")
                }
                // Nested logical members are MATCHES, not independent DNS
                // actions. Strategy belongs only on the parent route action.
            }
        }
        narrowRules(dns.optJSONArray("rules"))
        return config
    }
}
