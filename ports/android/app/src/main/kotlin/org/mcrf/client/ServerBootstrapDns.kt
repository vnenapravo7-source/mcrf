package org.mcrf.client

import org.json.JSONObject

/** Server bootstrap is not DNS for a routed website. Apply only to the private
 * prepared config, never to persisted/imported profiles. Explicit profile
 * resolvers retain priority. All destination Direct/VPN DNS rules stay intact. */
internal object ServerBootstrapDns {
    /** Catalogue provenance, not a guess from a resolver named bootstrap. A
     * matching explicit profile resolver must still keep its own semantics. */
    fun implicitTags(entries:List<ServerImport.Server>):Set<String> = entries.filter {entry->
        entry.configuration==null&&entry.carrier==null&&(entry.outbound?:entry.endpoint)?.let {!it.has("domain_resolver")}==true
    }.mapTo(linkedSetOf(),ServerImport::tag)
    fun apply(config:JSONObject,serverTag:String,strategy:String,implicitTags:Set<String> = emptySet()) {
        require(serverTag.isNotBlank())
        require(strategy in setOf("","prefer_ipv4","prefer_ipv6","ipv4_only","ipv6_only"))
        fun domain(host:String)=host.isNotBlank()&&RouteRules.literal(host)==null
        fun bind(node:JSONObject) {
            if(node.has("domain_resolver")){
                val old=node.optJSONObject("domain_resolver")
                val generated=node.optString("tag") in implicitTags&&old!=null&&
                    old.keys().asSequence().toSet()==setOf("server","strategy")&&old.optString("server")=="bootstrap"&&old.optString("strategy")=="prefer_ipv4"
                if(!generated)return
            }
            node.put("domain_resolver",JSONObject().put("server",serverTag).apply {
                if(strategy.isNotEmpty())put("strategy",strategy)
            })
        }
        config.optJSONArray("outbounds")?.let {nodes->
            repeat(nodes.length()){index->
                val node=nodes.getJSONObject(index)
                // Selectors and direct destinations are not remote server names.
                if(node.optString("type") !in setOf("direct","block","dns","selector","urltest")&&domain(node.optString("server")))bind(node)
            }
        }
        config.optJSONArray("endpoints")?.let {nodes->
            repeat(nodes.length()){index->
                val node=nodes.getJSONObject(index)
                if(node.optString("type")=="wireguard")node.optJSONArray("peers")?.let {peers->
                    // Dial fields belong to the endpoint, NOT its peer objects.
                    if((0 until peers.length()).any {peer->domain(peers.getJSONObject(peer).optString("address"))})bind(node)
                }
            }
        }
    }
}
