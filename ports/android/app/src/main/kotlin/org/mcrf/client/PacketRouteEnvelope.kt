package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Immutable captured route handoff, not a native/drop capability declaration.
 * Host builds TUN routes from the SAME projection which the worker independently
 * recomputes against the original profile. No private keys/UAPI in this value. */
object PacketRouteEnvelope {
    enum class Admission { LEGACY_IP, ATOMIC_IP_BLOCK }
    const val BLOCK_METADATA="org.mcrf.engine.protocol.block-capture"
    const val BLOCK_CONTRACT="atomic_tun_peer_v1"
    /** Parses a declaration, NOT proof of identity or native acceptance. The
     * Android caller must bind it to the SAME signer/ABI/SHA-verified APK. */
    fun admission(marker:Any?):Admission {
        if(marker==null)return Admission.LEGACY_IP
        require(marker is String&&marker==BLOCK_CONTRACT){"PACKET_BLOCK_CONTRACT"}
        return Admission.ATOMIC_IP_BLOCK
    }
    class Captured internal constructor(val projection:PacketIpRoutes.Projection,val requiresCapturedDrop:Boolean,val settings:String,dns:List<String>?=null,val managed:PacketManagedDnsPlan.Plan?=null) {
        val dns:List<String>?=dns?.let {java.util.Collections.unmodifiableList(it.toList())}
        override fun toString()="PacketRouteEnvelope(redacted)"
    }
    private fun exact(value:JSONObject,names:Set<String>) {
        require(value.keys().asSequence().toSet()==names) {"PACKET_ROUTE_FIELDS"}
    }
    private fun strings(value:Any?,limit:Int):List<String> {
        require(value is JSONArray&&value.length()<=limit) {"PACKET_ROUTE_ARRAY_BOUND"}
        return (0 until value.length()).map {index->
            val text=value.get(index);require(text is String&&text.length in 3..64) {"PACKET_ROUTE_CIDR_TYPE"};text
        }
    }
    fun capture(allowedIps:List<String>,rules:List<PacketIpRoutes.Rule>):Captured {
        val projection=PacketIpRoutes.project(allowedIps,rules)
        val rows=JSONArray()
        rules.forEach {rows.put(JSONObject().put("target",it.target.name).put("cidrs",JSONArray(it.cidrs)))}
        val settings=JSONObject().put("schema",1).put("rules",rows).put("tun",JSONArray(projection.tun)).put("peer",JSONArray(projection.peer)).toString()
        require(settings.length<=1048576) {"PACKET_ROUTE_SETTINGS_BOUND"}
        return Captured(projection,rules.any {it.target==PacketIpRoutes.Target.BLOCK},settings)
    }
    fun decode(settings:String,allowedIps:List<String>,addresses:List<String> = emptyList()):Captured {
        require(settings.length<=1048576) {"PACKET_ROUTE_SETTINGS_BOUND"}
        val value=JSONObject(settings)
        if(value.length()==0)return Captured(PacketIpRoutes.project(allowedIps,emptyList()),false,"{}") // existing fixture/legacy path
        val version=value.get("schema")
        require((version is Int||version is Long)&&(version as Number).toLong() in 1L..3L) {"PACKET_ROUTE_SCHEMA"}
        val dnsBound=(version as Number).toLong()>=2L
        val managed=(version as Number).toLong()==3L
        exact(value,setOf("schema","rules","tun","peer")+if(dnsBound)setOf("dns") else emptySet())
        val rows=value.get("rules");require(rows is JSONArray&&rows.length()<=1024) {"PACKET_ROUTE_RULE_BOUND"}
        var count=0
        val rules=(0 until rows.length()).map {index->
            val row=rows.get(index);require(row is JSONObject) {"PACKET_ROUTE_RULE_TYPE"}
            exact(row,setOf("target","cidrs"))
            val target=row.get("target");require(target is String&&target in setOf("VPN","DIRECT","BLOCK")) {"PACKET_ROUTE_TARGET"}
            val cidrs=strings(row.opt("cidrs"),4096);count+=cidrs.size;require(count<=4096) {"PACKET_ROUTE_CIDR_BOUND"}
            PacketIpRoutes.Rule(cidrs,PacketIpRoutes.Target.valueOf(target))
        }
        val base=capture(allowedIps,rules)
        val captured=if(managed){
            require(addresses.isNotEmpty()) {"PACKET_DNS_ORIGINAL_ADDRESSES_REQUIRED"}
            withManagedDns(base,PacketManagedDnsPlan.decode(value.opt("dns")),PacketManagedDnsPlan.aliasesForAddresses(addresses))
        }else base
        // Exact ordered canonical projections, not a caller-chosen pair of
        // independent arrays. Rebinding either side fails before native start.
        require(strings(value.opt("tun"),8192)==captured.projection.tun&&strings(value.opt("peer"),8192)==captured.projection.peer) {"PACKET_ROUTE_PROJECTION_MISMATCH"}
        return if(dnsBound&&!managed)withDns(captured,PacketDnsSnapshot.decode(value.opt("dns"))) else captured
    }

    fun withDns(captured:Captured,servers:List<String>):Captured {
        require(captured.settings!="{}") {"PACKET_DNS_ROUTE_SNAPSHOT_REQUIRED"}
        val dns=PacketDnsSnapshot.numeric(servers)
        val settings=JSONObject(captured.settings).put("schema",2).put("dns",PacketDnsSnapshot.encode(dns)).toString()
        require(settings.length<=1048576) {"PACKET_ROUTE_SETTINGS_BOUND"}
        return Captured(captured.projection,captured.requiresCapturedDrop,settings,dns)
    }

    fun withManagedDns(captured:Captured,plan:PacketManagedDnsPlan.Plan,aliases:List<String>):Captured {
        require(captured.settings!="{}"&&captured.managed==null){"PACKET_DNS_ROUTE_SNAPSHOT_REQUIRED"}
        val projection=PacketManagedDnsPlan.projection(captured.projection,aliases)
        val settings=JSONObject(captured.settings).put("schema",3).put("dns",plan.json())
            .put("tun",JSONArray(projection.tun)).put("peer",JSONArray(projection.peer)).toString()
        require(settings.length<=1048576){"PACKET_ROUTE_SETTINGS_BOUND"}
        return Captured(projection,captured.requiresCapturedDrop,settings,aliases,plan)
    }

    /** Independent worker admission against the ORIGINAL profile, never a host-edited
     * peer profile. BLOCK remains fenced until actual captured-drop/leak acceptance.
     * Wiring this boundary does not declare a new native capability. */
    fun workerPlan(original:CompactWireGuardPlan.Plan,settings:String,admission:Admission=Admission.LEGACY_IP,managedDnsAdmission:Boolean=false):CompactWireGuardPlan.Plan {
        val captured=decode(settings,original.routes,original.addresses)
        require(!captured.requiresCapturedDrop||admission==Admission.ATOMIC_IP_BLOCK) {"PACKET_CAPTURED_DROP_NOT_ACCEPTED"}
        if(captured.managed!=null){
            require(managedDnsAdmission){"Компактный WG: установленный модуль ещё не подтвердил управляемый DNS. Настройки не изменены."}
            PacketManagedDnsPlan.validate(captured.managed,original,captured.projection)
        }else captured.dns?.let {PacketDnsSnapshot.validate(original,captured.projection,it)}
        // Existing owned packet fixtures deliberately retain the original peer.
        if(settings=="{}")return original
        return CompactWireGuardPlan.withPeerRoutes(original,captured.projection.peer)
    }

    /** Candidate-only admission for the already-owned DEBUG packet fixture. BOTH
     * host and worker must independently check DEBUG/fixture ownership before calling.
     * No arbitrary user ranges, DNS, profile or rules can use this boundary. The
     * production workerPlan gate above deliberately remains unchanged. */
    fun ownedBlockFixturePlan(original:CompactWireGuardPlan.Plan,settings:String):CompactWireGuardPlan.Plan {
        require(original.mtu==1280&&original.addresses==listOf("10.77.0.2/32","fd77::2/128")&&
            original.dns==listOf("10.77.0.1")&&original.routes==listOf("10.77.0.1/32","10.77.0.3/32","fd77::1/128","fd77::3/128")) {"PACKET_BLOCK_FIXTURE_PROFILE"}
        val expected=capture(original.routes,listOf(PacketIpRoutes.Rule(listOf("10.77.0.3/32","fd77::3/128"),PacketIpRoutes.Target.BLOCK)))
        val captured=decode(settings,original.routes)
        require(captured.settings==expected.settings&&captured.requiresCapturedDrop&&
            captured.projection.peer.map {ClientTunCidr.parse(it)}==listOf("10.77.0.1/32","fd77::1/128").map {ClientTunCidr.parse(it)}) {"PACKET_BLOCK_FIXTURE_PROJECTION"}
        return CompactWireGuardPlan.withPeerRoutes(original,captured.projection.peer)
    }
}
