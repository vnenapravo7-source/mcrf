package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** One external dispatcher owns original UID/rules/DNS/TUN; selected Steer
 * servers still run their ACTUAL protocol in fresh Steer workers. This plan
 * does not change the saved core/server or grant module/install admission.
 * Existing human WG precedence is preserved: installed sing-box handles WG.
 * The compact packet-pipe role is not an excuse to override that precedence. */
internal class NativeRouteDispatchPlan private constructor(private val captured:List<Binding>) {
    class Binding internal constructor(val id:String,val host:String,private val profile:String,tags:List<String>,outbound:JSONObject,
        private val assistedOriginal:ServerImport.Server?=null) {
        val tags:List<String> = java.util.Collections.unmodifiableList(tags.toList())
        private val node=JSONObject(outbound.toString()).apply {remove("tag")}
        fun secretProfile():String=profile
        fun assistedEntry():ServerImport.Server?=assistedOriginal?.let {entry->
            ServerImport.Server(original=entry.original,name=entry.name,protocol=entry.protocol,host=entry.host,port=entry.port,
                outbound=JSONObject(requireNotNull(entry.outbound).toString()),endpoint=null,configuration=null,profileDns=entry.profileDns.toList())
        }
        internal fun matches(value:JSONObject):Boolean {
            val actual=JSONObject(value.toString()).apply {remove("tag")}
            // Generated server bootstrap belongs to dispatcher preparation, not
            // URI options. An explicit imported resolver was already rejected
            // by the strict effective-profile roundtrip; never discard it here.
            if(!node.has("domain_resolver"))actual.remove("domain_resolver")
            // CoreTuning materializes the secure default on prepared outputs.
            // Legacy VMess TLS deliberately omits it in the saved profile.
            // Accept only that exact false Boolean, never an insecure override
            // or a changed explicit certificate policy.
            if(node.optJSONObject("tls")?.let {it.optBoolean("enabled")&&!it.has("insecure")}==true){
                actual.optJSONObject("tls")?.let {if(it.opt("insecure")==false)it.remove("insecure")}
            }
            val host=node.optString("server")
            if(host.equals("localhost",true)||RouteRules.literal(host)?.isLoopbackAddress==true){
                for((field,value) in mapOf("inet4_bind_address" to "127.0.0.1","inet6_bind_address" to "::1")){
                    if(!node.has(field)&&actual.optString(field)==value)actual.remove(field)
                }
            }
            return RoutingDefaultsEdits.equivalent(node,actual)
        }
        override fun toString()="NativeRouteBinding(redacted)"
    }
    val bindings:List<Binding> = java.util.Collections.unmodifiableList(captured.toList())
    /** Replaces exact protocol nodes only, retaining route/DNS tags/order and
     * all other endpoints. No selectors are flattened or global final changed. */
    fun replace(source:JSONObject,bridges:Map<String,JSONObject>):JSONObject {
        require(bridges.keys==captured.map {it.id}.toSet()){ "NATIVE_ROUTE_BRIDGE_SCOPE" }
        val result=JSONObject(source.toString())
        val nodes=result.getJSONArray("outbounds")
        val required=captured.flatMap {binding->binding.tags.map {it to binding}}.toMap()
        val seen=hashSetOf<String>();val replaced=JSONArray()
        repeat(nodes.length()){index->
            val node=nodes.getJSONObject(index);val tag=node.getString("tag")
            require(seen.add(tag)){"NATIVE_ROUTE_DUPLICATE_TAG"}
            val binding=required[tag]
            if(binding==null)replaced.put(node)else {
                require(binding.matches(node)){"Параметры сервера правила изменились после подготовки. Повторите подключение."}
                val bridge=JSONObject(requireNotNull(bridges[binding.id]).toString())
                require(bridge.keys().asSequence().toSet()==setOf("type","server","server_port","version","username","password")&&
                    bridge.optString("type")=="socks"&&bridge.optString("server")=="127.0.0.1"&&bridge.optString("version")=="5"&&
                    bridge.optInt("server_port") in 1024..65535&&bridge.optString("username").matches(Regex("[0-9a-f]{32}"))&&
                    bridge.optString("password").matches(Regex("[0-9a-f]{64}"))){"NATIVE_ROUTE_BRIDGE_IDENTITY"}
                replaced.put(bridge.put("type",TYPE).put("tag",tag).put("inet4_bind_address","127.0.0.1").put("inet6_bind_address","::1"))
            }
        }
        require(required.keys.all {it in seen}){"NATIVE_ROUTE_MISSING_TAG"}
        result.put("outbounds",replaced)
        val numericTargets=required.keys.toMutableSet()
        // A rule may point at an automatic server pool rather than directly at
        // a native bridge. Resolve its destination through the same rule DNS
        // before selecting any member; never ask a private worker for DNS.
        var expanded:Boolean
        do {
            expanded=false
            repeat(replaced.length()){i->val node=replaced.getJSONObject(i)
                if(node.optString("type") in setOf("urltest","selector"))node.optJSONArray("outbounds")?.let {members->
                    if((0 until members.length()).any {members.getString(it) in numericTargets}&&numericTargets.add(node.getString("tag")))expanded=true
                }
            }
        }while(expanded)
        // SOCKS5 deliberately preserves FQDN by default in the pinned upstream.
        // Our private native boundary accepts ONLY numeric targets. Resolve in
        // the dispatcher with the original metadata/DNS rules BEFORE routing,
        // never let the bridge consult physical/default DNS for a website.
        val route=result.getJSONObject("route");val rules=route.optJSONArray("rules")?:JSONArray()
        val ordered=JSONArray()
        repeat(rules.length()){index->
            val rule=rules.getJSONObject(index)
            if(rule.optString("action","route")=="route"&&rule.optString("outbound") in numericTargets){
                val resolve=JSONObject(rule.toString())
                routeActionFields.forEach(resolve::remove)
                ordered.put(resolve.put("action","resolve"))
            }
            ordered.put(rule)
        }
        if(route.optString("final") in numericTargets)ordered.put(JSONObject().put("action","resolve"))
        route.put("rules",ordered)
        return result
    }
    override fun toString()="NativeRouteDispatchPlan(servers=${captured.size})"
    companion object {
        const val TYPE="mcrf_numeric_socks"
        const val CAPABILITY="owned_numeric_protocol_socks_v1"
        const val METADATA="org.mcrf.engine.protocol-route-dispatcher"
        // Imported independent protocols absent from Steer, but registered in
        // the pinned dispatcher include/{registry,quic,naive}.go. They remain
        // their OWN protocol, not a SOCKS/Steer approximation. Actual installed
        // native validation is still mandatory before start.
        private val dispatcherProtocols=setOf("hysteria","tuic","anytls","ssh","naive")
        // Exact RawRouteOptionsActionOptions of pinned 3d3d3db3; match fields
        // survive unchanged, the original route/action parameters stay on the
        // original rule. Do not apply route options to a resolve action.
        private val routeActionFields=setOf("action","outbound","override_address","override_port","network_strategy","fallback_delay",
            "udp_disable_domain_unmapping","udp_connect","udp_timeout","tls_fragment","tls_fragment_fallback_delay","tls_record_fragment","tls_spoof","tls_spoof_method")
        fun forSteer(main:ServerImport.Server,additional:RouteServerPlan,optional:OptionalCoreSettings=OptionalCoreSettings(),
                     core:CoreTuningSettings?=null):NativeRouteDispatchPlan {
            require(SteerAutomaticPool.compatible(main)){"Основной сервер нельзя точно запустить через Steer."}
            fun module(entry:ServerImport.Server):OptionalCoreConfig.Request?=entry.outbound?.let {node->
                if(core==null)OptionalCoreConfig.prepare(node,optional)else OptionalCoreTuning.prepare(node,optional,core).request
            }
            require(module(main)==null){"Параметры основного сервера требуют Xray/TLS Core, а выбран Steer. Другой протокольный движок автоматически не подставляется."}
            val all=linkedMapOf<String,Pair<ServerImport.Server,MutableList<String>>>()
            fun capture(entry:ServerImport.Server,tag:String){
                // An explicitly selected extra server may require its OWN
                // protocol module. Leave that exact node to OptionalCorePlan;
                // never roundtrip its TLS/Xray extensions through a Steer URI.
                if(entry.id!=main.id&&module(entry)!=null)return
                if(!SteerProbePlan.supports(entry)&&!CompactProtocolPolicy.eligible(entry)){
                    require(entry.carrier!=null||WgAddonPolicy.isWireGuard(entry.protocol)||
                        entry.protocol in dispatcherProtocols&&entry.outbound?.optString("type")==entry.protocol){
                        "Протокол сервера «${entry.name}» не поддержан выбранным Steer; другой протокол автоматически не подставляется."
                    }
                    return
                }
                val previous=all[entry.id]
                if(previous!=null){
                    require(SteerProbePlan.fingerprint(previous.first)==SteerProbePlan.fingerprint(entry)){"NATIVE_ROUTE_PROFILE_IDENTITY"}
                    require(tag !in previous.second){"NATIVE_ROUTE_DUPLICATE_TAG"};previous.second.add(tag)
                }else all[entry.id]=entry to mutableListOf(tag)
            }
            capture(main,ServerImport.tag(main))
            additional.entries.forEach {capture(it,RouteServerPlan.tag(it.id))}
            require(all.size in 1..8){"До 8 серверов Steer одновременно в правилах, включая основной."}
            val bindings=all.values.map {(entry,tags)->
                val assisted=CompactProtocolPolicy.eligible(entry)
                // A compact protocol has no native Steer URI until its owned
                // child supplies fresh authenticated ingress. Never use its
                // original unsupported URI as a pretend native profile.
                Binding(entry.id,entry.host,if(assisted)""else SteerProbePlan.profile(entry),tags,requireNotNull(entry.outbound),
                    if(assisted)ServerImport.Server(original=entry.original,name=entry.name,protocol=entry.protocol,host=entry.host,port=entry.port,
                        outbound=JSONObject(requireNotNull(entry.outbound).toString()),endpoint=null,configuration=null,profileDns=entry.profileDns.toList())else null)
            }
            return NativeRouteDispatchPlan(bindings)
        }
    }
}
