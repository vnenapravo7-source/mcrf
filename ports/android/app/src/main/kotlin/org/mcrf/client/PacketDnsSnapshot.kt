package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/** Current numeric tunnel-DNS handoff only. NOT a managed/encrypted resolver.
 * Bind the host's selected ordered DNS list to the same settings SHA as routes,
 * and independently revalidate profile precedence, peer coverage and families.
 * A future richer DNS mode must get its own implementation/admission, not reuse
 * this mode while silently bypassing encryption, Direct rules or IPv4 policy. */
object PacketDnsSnapshot {
    const val MODE="profile_or_selected_tunnel_udp53"
    fun numeric(servers:List<String>):List<String> {
        require(servers.isNotEmpty()&&servers.size<=64) {"PACKET_DNS_SERVER_BOUND"}
        require(servers.all {it.length in 3..45&&RouteRules.literal(it)!=null}) {"PACKET_DNS_NUMERIC_REQUIRED"}
        return Collections.unmodifiableList(servers.toList())
    }
    fun encode(servers:List<String>):JSONObject=JSONObject().put("mode",MODE).put("servers",JSONArray(numeric(servers)))
    fun decode(value:Any?):List<String> {
        require(value is JSONObject&&value.keys().asSequence().toSet()==setOf("mode","servers")&&value.opt("mode")==MODE) {"PACKET_DNS_FIELDS"}
        val list=value.opt("servers");require(list is JSONArray&&list.length() in 1..64) {"PACKET_DNS_SERVER_BOUND"}
        val servers=(0 until list.length()).map {index->val server=list.get(index);require(server is String) {"PACKET_DNS_SERVER_TYPE"};server}
        return numeric(servers)
    }
    fun validate(original:CompactWireGuardPlan.Plan,projection:PacketIpRoutes.Projection,servers:List<String>) {
        numeric(servers)
        require(original.dns.isEmpty()||servers==original.dns) {"PACKET_DNS_PROFILE_PRECEDENCE"}
        require(servers.all {ip->
            val address=requireNotNull(RouteRules.literal(ip));val host=ClientTunCidr.parse("$ip/${address.address.size*8}")
            projection.peer.any {ClientTunCidr.parse(it).overlaps(host)}&&
                original.addresses.any {ClientTunCidr.parse(it,false).bits==host.bits}
        }) {"Компактный WG: DNS должен быть доступен внутри туннеля; прямой DNS не подставляется."}
    }
}
