package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** DEBUG-owned TUN fixture, never a saved user configuration.
 * Actual RouteRules compiler decides Direct/proxy; only terminal Direct dials
 * are redirected to the invocation's own physical HTTP origin after matching.
 * CIDR and optional owned-host DNS modes do NOT establish IPv6/Internet parity.
 */
internal object OwnedDirectVpnPlan {
    fun hostname(tag:String):String {
        require(tag.matches(Regex("fixture-owned-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        return tag.removePrefix("fixture-owned-").replace("-","")+".fixture.test"
    }
    fun build(address:String,port:Int,tag:String,inverted:Boolean,negative:Boolean,domain:Boolean=false):JSONObject {
        require(RouteRules.literal(address) is java.net.Inet4Address&&port in 1024..65535)
        require(tag.matches(Regex("fixture-owned-[0-9a-f-]{36}")))
        val direct="$tag-direct";val health="mcrf-loopback-owned-health"
        val source=JSONObject().put("inbounds",JSONArray()).put("outbounds",JSONArray()
            .put(JSONObject().put("type","direct").put("tag",direct))
            .put(JSONObject().put("type","direct").put("tag",health))
            // The origin refuses CONNECT, recording the wrong outbound hop.
            .put(JSONObject().put("type","http").put("tag",tag).put("server",address).put("server_port",port)))
            .put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("type","hosts").put("tag","fixture-dns")))
                .put("final","fixture-dns").put("rules",JSONArray().put(JSONObject().put("action","reject"))))
            .put("route",JSONObject().put("final",tag).put("rules",JSONArray()
                .put(JSONObject().put("ip_cidr",JSONArray().put("$address/32")).put("network","tcp").put("port",port).put("action","route").put("outbound",health))))
        val settings=RouteRules.defaults().put("defaultTarget",if(inverted)"direct"else"proxy")
        val host=if(domain)hostname(tag)else""
        if(domain){
            val dns=source.getJSONObject("dns")
            dns.getJSONArray("servers").getJSONObject(0).put("predefined",JSONObject().put(host,JSONArray().put("198.18.0.2")))
            dns.put("reverse_mapping",true).put("strategy","ipv4_only")
                .put("rules",JSONArray().put(JSONObject().put("domain",JSONArray().put(host)).put("action","route").put("server","fixture-dns"))
                    .put(JSONObject().put("action","predefined").put("rcode","NXDOMAIN")))
        }
        settings.getJSONArray("rules").put(JSONObject().put("id","owned-cidr").put("name","Owned TUN CIDR")
            .put("text",if(domain)"DOMAIN,"+(if(negative)"wrong-"else"")+host else if(negative)"203.0.113.0/24"else"198.18.0.2/32").put("mode","addresses")
            .put("target",if(inverted)"proxy"else"direct").put("invert",inverted).put("enabled",true))
        val compiled=RouteRules.apply(source,settings,true)
        val route=compiled.getJSONObject("route");val rules=route.getJSONArray("rules")
        val guarded=JSONArray().put(JSONObject().put("type","logical").put("mode","or").put("invert",true)
            .put("rules",JSONArray()
                .put(JSONObject().put("inbound",JSONArray().put("tun-in")).put("network","tcp").put("ip_cidr",JSONArray().put("198.18.0.2/32")).put("port",80))
                .put(JSONObject().put("network","tcp").put("ip_cidr",JSONArray().put("$address/32")).put("port",port)))
            .put("action","reject"))
        if(domain)guarded.getJSONObject(0).getJSONArray("rules").put(JSONObject()
            .put("inbound",JSONArray().put("tun-in")).put("network",JSONArray().put("tcp").put("udp"))
            .put("ip_cidr",JSONArray().put("172.19.0.2/32").put("fdfe:dcba:9876::2/128")).put("port",53))
        repeat(rules.length()){index->
            val rule=rules.getJSONObject(index)
            if(rule.optString("outbound")==direct){
                check(rule.optString("action")=="route")
                rule.put("override_address",address).put("override_port",port)
            }
            guarded.put(rule)
        }
        // Materialize only this owned fixture's final action so Direct's native
        // dial reaches the peer, without rewriting conditions/order/default.
        val terminal=JSONObject().put("action","route").put("outbound",route.getString("final"))
        if(route.getString("final")==direct)terminal.put("override_address",address).put("override_port",port)
        guarded.put(terminal);route.put("rules",guarded)
        check((0 until rules.length()).count {rules.getJSONObject(it).optString("action")=="resolve"}==if(domain)0 else 1)
        check(route.getString("final")==if(inverted)direct else tag)
        return compiled
    }
}
