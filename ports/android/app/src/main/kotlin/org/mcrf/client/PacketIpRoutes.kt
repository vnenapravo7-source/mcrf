package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Ordered IP-only route projection for a packet backend. Not a domain/app dispatcher.
 * BLOCK cannot be implemented by removing a TUN route: that would leak traffic directly.
 * Callers must reject unsupported conditions before projecting their complete snapshot. */
object PacketIpRoutes {
    enum class Target { VPN, DIRECT, BLOCK }
    data class Rule(val cidrs:List<String>,val target:Target)
    /** Pure bounded IP-only expression compiler. One instance per routing
     * snapshot shares the node/CIDR/work budgets across ALL enabled rules.
     * Domain/app/port/Geo fields are rejected, never silently stripped. */
    class Conditions {
        private var nodes=0
        private var terms=0
        private var work=0L
        private val universe=listOf(ClientTunCidr.parse("0.0.0.0/0"),ClientTunCidr.parse("::/0"))
        private fun tick(count:Int){work+=count.toLong();require(work<=65536L){"PACKET_CONDITION_WORK_LIMIT"}}
        private fun bounded(values:List<ClientTunCidr>):List<ClientTunCidr>{
            require(values.size<=8192){"PACKET_CONDITION_RANGE_LIMIT"};return values
        }
        private fun excluding(values:List<ClientTunCidr>,removed:List<ClientTunCidr>):List<ClientTunCidr>{
            var result=values
            for(range in removed){
                if(result.isEmpty())break
                tick(result.size)
                result=bounded(result.flatMap {it.subtract(range)}.distinct())
            }
            return result
        }
        private fun union(left:List<ClientTunCidr>,right:List<ClientTunCidr>)=
            bounded(left+excluding(right,left))
        private fun intersection(left:List<ClientTunCidr>,right:List<ClientTunCidr>):List<ClientTunCidr>{
            val result=mutableListOf<ClientTunCidr>()
            for(first in left)for(second in right){
                tick(1)
                if(first.overlaps(second))result+=if(first.prefix>=second.prefix)first else second
                require(result.size<=8192){"PACKET_CONDITION_RANGE_LIMIT"}
            }
            return result.distinct()
        }
        private fun fields(row:JSONObject,allowed:Set<String>){
            require(row.keys().asSequence().all {it in allowed}){"PACKET_CONDITION_FIELDS"}
        }
        private fun row(value:JSONObject,depth:Int):List<ClientTunCidr>{
            require(depth<=16&&++nodes<=4096){"PACKET_CONDITION_NODE_LIMIT"}
            require(!value.has("invert")||value.opt("invert") is Boolean){"PACKET_CONDITION_INVERT_TYPE"}
            require(!value.has("type")||value.opt("type") is String){"PACKET_CONDITION_TYPE"}
            val ranges=when(value.optString("type","default")){
                "default"->{
                    fields(value,setOf("type","invert","ip_cidr"))
                    val raw=value.opt("ip_cidr")
                    val entries=if(raw is String)JSONArray().put(raw)else raw
                    require(entries is JSONArray&&entries.length() in 1..4096){"PACKET_CONDITION_CIDRS"}
                    terms+=entries.length();require(terms<=4096){"PACKET_CONDITION_CIDR_LIMIT"}
                    var result=emptyList<ClientTunCidr>()
                    repeat(entries.length()){at->
                        val text=entries.get(at);require(text is String&&text.length in 3..64){"PACKET_CONDITION_CIDR_TYPE"}
                        result=union(result,listOf(ClientTunCidr.parse(text)))
                    }
                    result
                }
                "logical"->{
                    fields(value,setOf("type","invert","mode","rules"))
                    val mode=value.opt("mode");require(mode=="and"||mode=="or"){"PACKET_CONDITION_LOGICAL_MODE"}
                    val children=value.opt("rules");require(children is JSONArray&&children.length() in 1..256){"PACKET_CONDITION_CHILDREN"}
                    var result=if(mode=="and")universe else emptyList()
                    // Inspect EVERY child even when the current set is empty/full:
                    // short circuiting must not hide an unsupported predicate.
                    repeat(children.length()){at->
                        val child=children.get(at);require(child is JSONObject){"PACKET_CONDITION_CHILD_TYPE"}
                        val compiled=row(child,depth+1)
                        result=if(mode=="and")intersection(result,compiled)else union(result,compiled)
                    }
                    result
                }
                else->throw IllegalArgumentException("PACKET_CONDITION_TYPE")
            }
            return if(value.optBoolean("invert",false))excluding(universe,ranges)else ranges
        }
        fun compile(value:JSONObject,invert:Boolean=false):List<String>{
            val ranges=row(value,0)
            return java.util.Collections.unmodifiableList((if(invert)excluding(universe,ranges)else ranges).map {it.toString()})
        }
    }
    fun compile(allowedIps:List<String>,rules:List<Rule>):List<String> {
        require(rules.none {it.target==Target.BLOCK}) {"Компактный WG: IP-блокировку нельзя заменять прямым выходом."}
        return project(allowedIps,rules).tun
    }
    /** Preparation only: BLOCK stays captured by Android while excluded from the sole peer.
     * Runtime must apply BOTH lists atomically, and reject unsupported app/domain conditions. */
    class Projection internal constructor(tun:List<String>,peer:List<String>) {
        val tun:List<String> = java.util.Collections.unmodifiableList(tun.toList())
        val peer:List<String> = java.util.Collections.unmodifiableList(peer.toList())
    }
    fun project(allowedIps:List<String>,rules:List<Rule>):Projection {
        require(allowedIps.isNotEmpty()&&allowedIps.size<=512&&rules.size<=1024) {"PACKET_ROUTE_LIMIT"}
        require(rules.sumOf {it.cidrs.size.toLong()}<=4096L) {"PACKET_ROUTE_LIMIT"}
        val allowed=allowedIps.map {ClientTunCidr.parse(it)}.distinct()
        var work=0L
        fun excluding(ranges:List<ClientTunCidr>,protected:List<ClientTunCidr>):List<ClientTunCidr> {
            var result=ranges.distinct()
            for(keep in protected) {
                if(result.isEmpty())break
                work+=result.size
                require(work<=65536L) {"Слишком сложные IP-правила для компактного WG. Упрости списки или выбери sing-box."}
                result=result.flatMap {it.subtract(keep)}.distinct()
                require(result.size<=8192) {"PACKET_ROUTE_LIMIT"}
            }
            return result
        }
        var claimed=emptyList<ClientTunCidr>()
        val direct=mutableListOf<ClientTunCidr>()
        val blocked=mutableListOf<ClientTunCidr>()
        for(rule in rules) {
            require(rule.cidrs.isNotEmpty()) {"PACKET_ROUTE_EMPTY"}
            val ranges=rule.cidrs.map {ClientTunCidr.parse(it)}.distinct()
            if(rule.target==Target.VPN)require(excluding(excluding(ranges,claimed),allowed).isEmpty()) {
                "Компактный WG: правило VPN выходит за AllowedIPs профиля; разрешения peer не расширены."
            }
            if(rule.target==Target.DIRECT)direct.addAll(excluding(ranges,claimed))
            if(rule.target==Target.BLOCK)blocked.addAll(excluding(ranges,claimed))
            claimed=(claimed+ranges).distinct()
            require(claimed.size<=4096&&direct.size<=8192&&blocked.size<=8192) {"PACKET_ROUTE_LIMIT"}
        }
        val allowedCapture=excluding(allowed,direct)
        // BLOCK outside the peer's AllowedIPs must ALSO stay captured. Merely
        // subtracting it from the peer would leave those packets on physical
        // routes. Add only uncovered ranges to avoid redundant TUN routes.
        val tun=(allowedCapture+excluding(blocked,allowedCapture)).distinct()
        require(tun.size<=8192) {"PACKET_ROUTE_LIMIT"}
        val peer=excluding(tun,blocked)
        return Projection(tun.map {it.toString()},peer.map {it.toString()})
    }
}
