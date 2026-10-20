package org.mcrf.client

/** One frozen pair: captured TUN routes and the sole peer's narrower AllowedIPs.
 * Preparation only. Production BLOCK admission stays closed until Android drop/leak tests pass. */
object PacketPeerProjection {
    class Prepared internal constructor(val tunRoutes:List<String>,val peerPlan:CompactWireGuardPlan.Plan,private val peerProfile:String,dns:List<String>) {
        val dnsServers:List<String> = java.util.Collections.unmodifiableList(dns.toList())
        /** Private key included: worker input only, never a log or saved catalogue replacement. */
        fun secretPeerProfile():String=peerProfile
        override fun toString()="PacketPeerProjection(redacted)"
    }
    fun prepare(original:String,dial:String,rules:List<PacketIpRoutes.Rule>,dns:List<String>):Prepared {
        val capturedDns=dns.toList()
        val profile=WireGuardProfile.parse(original)
        val before=CompactWireGuardPlan.prepare(profile,dial)
        val projection=PacketIpRoutes.project(before.routes,rules)
        require(projection.tun.isNotEmpty()&&projection.peer.isNotEmpty()) {"Нет доступного выхода VPN после IP-правил."}
        require(projection.peer.size<=256) {"Слишком много сетей для компактного WG."}
        require(capturedDns.isNotEmpty()&&capturedDns.size<=16) {"Нужен явно выбранный DNS туннеля."}
        require(before.dns.isEmpty()||before.dns==capturedDns) {"DNS WG-профиля нельзя незаметно заменить общим DNS."}
        require(capturedDns.all {ip->
            val address=RouteRules.literal(ip)?:return@all false
            val host=ClientTunCidr.parse("$ip/${address.address.size*8}")
            before.addresses.any {ClientTunCidr.parse(it,false).bits==host.bits}&&
                projection.peer.any {ClientTunCidr.parse(it).overlaps(host)}
        }) {"IP-правила блокируют или выводят DNS из туннеля; прямой DNS не подставляется."}
        val canonical=profile.conf()
        val replacement="AllowedIPs = "+projection.peer.joinToString(", ")
        val expression=Regex("(?m)^AllowedIPs = .*$")
        val projected=if(expression.containsMatchIn(canonical))canonical.replace(expression,replacement)else canonical+"\n$replacement\n"
        val peer=CompactWireGuardPlan.prepare(WireGuardProfile.parse(projected),dial)
        require(peer.addresses==before.addresses&&peer.mtu==before.mtu&&peer.dns==before.dns&&peer.routes==projection.peer)
        return Prepared(projection.tun,peer,projected,capturedDns)
    }
}
