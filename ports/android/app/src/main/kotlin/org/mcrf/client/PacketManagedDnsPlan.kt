package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.engine.protocol.dns.v1.ManagedDnsPacketBackend
import java.util.Collections

/** Bounded managed DNS plan, separate from the legacy UDP53 DNS handoff. No IO.
 * Does not claim per-domain packet routing/app-DNS/DoQ/H3/FakeIP support. Those
 * unsupported conditions must still reject before any worker/TUN side effect.
 * The optional APK declaration and native implementation are independent gates.
 */
object PacketManagedDnsPlan {
    const val METADATA="org.mcrf.engine.protocol.managed-dns"
    const val CONTRACT=ManagedDnsPacketBackend.CONTRACT
    fun admission(marker:Any?):Boolean {if(marker==null)return false;require(marker is String&&marker==CONTRACT){"PACKET_MANAGED_DNS_CONTRACT"};return true}
    class Endpoint internal constructor(val type:String,val server:String,val port:Int,val path:String,val tlsName:String) {
        fun json():JSONObject=JSONObject().put("type",type).put("server",server).put("port",port).put("path",path).put("tlsName",tlsName)
        override fun toString()="PacketDnsEndpoint(redacted)"
    }
    class Plan internal constructor(remote:List<Endpoint>,val direct:Endpoint,val local:Endpoint,val finalDirect:Boolean,
        val ipv4Only:Boolean,val remoteNoAAAA:Boolean,val directNoAAAA:Boolean,val cacheCapacity:Int,val timeoutMillis:Int,
        hosts:JSONObject,originalProfileDns:List<String>,val strategy:String) {
        val remote:List<Endpoint> = Collections.unmodifiableList(remote.toList())
        val originalProfileDns:List<String> = Collections.unmodifiableList(originalProfileDns.toList())
        private val hostJson=hosts.toString()
        fun hosts():JSONObject=JSONObject(hostJson)
        fun json():JSONObject=JSONObject().put("mode",CONTRACT).put("remote",JSONArray(remote.map {it.json()}))
            .put("direct",direct.json()).put("local",local.json()).put("finalDirect",finalDirect).put("ipv4Only",ipv4Only)
            .put("remoteNoAAAA",remoteNoAAAA).put("directNoAAAA",directNoAAAA).put("cacheCapacity",cacheCapacity)
            .put("timeoutMillis",timeoutMillis).put("hosts",JSONObject(hostJson)).put("profileDns",JSONArray(originalProfileDns)).put("strategy",strategy)
        override fun toString()="PacketManagedDnsPlan(redacted)"
    }
    private val endpointFields=setOf("type","server","port","path","tlsName")
    private fun validName(text:String):Boolean = text.length in 1..253&&text.split('.').all {label->
        label.length in 1..63&&label.first().isLetterOrDigit()&&label.last().isLetterOrDigit()&&label.all {it in 'a'..'z'||it in 'A'..'Z'||it in '0'..'9'||it=='-'}
    }
    private fun endpoint(value:JSONObject):Endpoint {
        require(value.keys().asSequence().toSet()==endpointFields) {"PACKET_DNS_ENDPOINT_FIELDS"}
        fun text(key:String)=value.get(key).also {require(it is String&&it.length<=1024){"PACKET_DNS_ENDPOINT_TYPE"}} as String
        val type=text("type");val server=text("server");val path=text("path");val name=text("tlsName")
        val raw=value.get("port");require(raw is Int&&raw in 1..65535){"PACKET_DNS_ENDPOINT_PORT"}
        require(type in setOf("system","udp","tcp","tls","https")) {"Компактный WG: этот транспорт DNS пока не поддерживается."}
        if(type=="system")require(server.isEmpty()&&path.isEmpty()&&name.isEmpty()&&raw==53) {"PACKET_DNS_SYSTEM_FIELDS"}
        else {
            require(RouteRules.literal(server)!=null&&!server.contains('%')||validName(server)){"PACKET_DNS_SERVER"}
            require(type=="https"&&path.startsWith('/')&&path.length<=1024&&path.all {it in '!'..'~'}||type!="https"&&path.isEmpty()){"PACKET_DNS_PATH"}
            require(if(type in setOf("tls","https"))RouteRules.literal(name)!=null&&!name.contains('%')||validName(name) else name.isEmpty()){"PACKET_DNS_TLS_NAME"}
        }
        return Endpoint(type,server,raw,path,name)
    }
    private fun fromSettings(settings:JSONObject):Endpoint {
        val value=DnsConfig.build(settings,"chosen","bootstrap")
        val type=value.getString("type")
        return endpoint(JSONObject().put("type",if(type=="local")"system" else type).put("server",value.optString("server"))
            .put("port",value.optInt("server_port",53)).put("path",value.optString("path"))
            .put("tlsName",value.optJSONObject("tls")?.optString("server_name").orEmpty()))
    }
    fun needed(state:JSONObject,original:CompactWireGuardPlan.Plan,ipv4Only:Boolean):Boolean = ipv4Only||AdvancedDnsConfig.value(state).optBoolean("enabled")||
        original.dns.isEmpty()&&DnsConfig.build(state.getJSONObject("globalDns"),"chosen","bootstrap").let {it.optString("type")!="udp"||it.optInt("server_port",53)!=53||RouteRules.literal(it.optString("server"))==null}

    fun prepare(state:JSONObject,original:CompactWireGuardPlan.Plan,ipv4Only:Boolean):Plan {
        val advanced=AdvancedDnsConfig.value(state);AdvancedDnsConfig.validate(advanced)
        val enabled=advanced.getBoolean("enabled")
        if(enabled){
            require(!advanced.getBoolean("fakeIp")&&!advanced.getBoolean("optimistic")&&advanced.getString("custom").isBlank()) {"Компактный WG: FakeIP, оптимистичный кэш и произвольный DNS-JSON пока недоступны; настройки не отброшены."}
        }
        val remote=if(original.dns.isNotEmpty())PacketDnsSnapshot.numeric(original.dns).map {endpoint(JSONObject().put("type","udp").put("server",it).put("port",53).put("path","").put("tlsName",""))}
            else listOf(fromSettings(state.getJSONObject("globalDns")))
        val system=JSONObject().put("provider","Системный").put("protocol","Системный")
        val local=fromSettings(if(enabled)advanced.getJSONObject("local") else system)
        require(local.type=="system"||RouteRules.literal(local.server)!=null) {"DNS для адресов серверов должен быть системным или с числовым адресом."}
        val direct=fromSettings(if(enabled)advanced.getJSONObject("direct") else system)
        val timeout=if(enabled){val text=advanced.getString("timeout");val factor=when{ text.endsWith("ms")->1L;text.endsWith('s')->1000L;else->60000L};val number=text.removeSuffix("ms").removeSuffix("s").removeSuffix("m").toLong();require(number*factor in 100L..30000L){"Компактный WG: время DNS должно быть от100ms до30s."};(number*factor).toInt()}else 5000
        val hosts=if(enabled&&advanced.getBoolean("hosts"))AdvancedDnsConfig.answers(advanced.getString("answers")) else JSONObject()
        val prepared=Plan(remote,direct,local,enabled&&advanced.getString("final")=="direct",ipv4Only,
            enabled&&advanced.getBoolean("remoteNoAAAA"),enabled&&advanced.getBoolean("directNoAAAA"),
            if(!enabled||advanced.getBoolean("cache"))if(enabled)advanced.getInt("capacity") else 1024 else 0,timeout,hosts,
            Collections.unmodifiableList(original.dns.toList()),if(enabled)advanced.getString("strategy") else "prefer_ipv4")
        // Host rejects the same bounds/types BEFORE TUN side effects that the
        // independently decoding worker will reject later.
        return decode(prepared.json())
    }
    fun decode(value:Any?):Plan {
        require(value is JSONObject&&value.keys().asSequence().toSet()==setOf("mode","remote","direct","local","finalDirect","ipv4Only","remoteNoAAAA","directNoAAAA","cacheCapacity","timeoutMillis","hosts","profileDns","strategy")&&value.opt("mode")==CONTRACT){"PACKET_DNS_MANAGED_FIELDS"}
        fun flag(key:String)=value.get(key).also {require(it is Boolean){"PACKET_DNS_MANAGED_BOOLEAN"}} as Boolean
        fun number(key:String,range:IntRange)=value.get(key).also {require(it is Int&&it in range){"PACKET_DNS_MANAGED_NUMBER"}} as Int
        val rem=value.getJSONArray("remote");require(rem.length() in 1..64){"PACKET_DNS_MANAGED_REMOTE_BOUND"}
        val profile=value.getJSONArray("profileDns");require(profile.length()<=64){"PACKET_DNS_PROFILE_BOUND"}
        val original=(0 until profile.length()).map {val ip=profile.get(it);require(ip is String&&RouteRules.literal(ip)!=null);ip}
        val local=endpoint(value.getJSONObject("local"));require(local.type=="system"||RouteRules.literal(local.server)!=null){"PACKET_DNS_BOOTSTRAP_RECURSION"}
        val hosts=value.getJSONObject("hosts");require(hosts.length()<=4096&&hosts.toString().length<=262144){"PACKET_DNS_HOSTS_BOUND"}
        hosts.keys().asSequence().forEach {name->require(name.isNotBlank()&&name.length<=253);val addresses=hosts.getJSONArray(name);require(addresses.length() in 1..64);repeat(addresses.length()){require(addresses.get(it) is String&&RouteRules.literal(addresses.getString(it))!=null)}}
        val strategy=value.get("strategy");require(strategy is String&&strategy in setOf("","prefer_ipv4","prefer_ipv6","ipv4_only","ipv6_only")){"PACKET_DNS_STRATEGY"}
        return Plan((0 until rem.length()).map {endpoint(rem.getJSONObject(it))},endpoint(value.getJSONObject("direct")),local,
            flag("finalDirect"),flag("ipv4Only"),flag("remoteNoAAAA"),flag("directNoAAAA"),number("cacheCapacity",0..65536),number("timeoutMillis",100..30000),hosts,Collections.unmodifiableList(original),strategy)
    }
    fun aliases(original:CompactWireGuardPlan.Plan):List<String> = aliasesForAddresses(original.addresses)
    fun aliasesForAddresses(addresses:List<String>):List<String> = addresses.map {ClientTunCidr.parse(it,false).bits}.distinct().map {if(it==32)ManagedDnsPacketBackend.ALIAS4 else ManagedDnsPacketBackend.ALIAS6}
    fun projection(original:PacketIpRoutes.Projection,aliases:List<String>):PacketIpRoutes.Projection {
        var peer=original.peer.map {ClientTunCidr.parse(it)}
        val controls=aliases.map {ClientTunCidr.parse("$it/${if(':' in it)128 else 32}")}
        controls.forEach {control->peer=peer.flatMap {it.subtract(control)}}
        require(peer.size<=8192){"PACKET_DNS_PEER_BOUND"}
        return PacketIpRoutes.Projection((original.tun+controls.map {it.toString()}).distinct(),peer.map {it.toString()})
    }
    fun validate(plan:Plan,original:CompactWireGuardPlan.Plan,projection:PacketIpRoutes.Projection){
        require(original.addresses.size in 1..16){"PACKET_DNS_SOURCE_BOUND"}
        require(plan.originalProfileDns==original.dns){"PACKET_DNS_PROFILE_CHANGED"}
        if(original.dns.isNotEmpty())require(plan.remote.map {it.server}==original.dns&&plan.remote.all {it.type=="udp"&&it.port==53}){"PACKET_DNS_PROFILE_PRECEDENCE"}
        val controls=aliases(original).map {RouteRules.literal(it)}
        require(original.addresses.none {address->controls.any {it==RouteRules.literal(address.substringBefore('/'))}}){"PACKET_DNS_ADDRESS_COLLISION"}
        for(endpoint in plan.remote+listOf(plan.direct,plan.local))require(controls.none {it==RouteRules.literal(endpoint.server)}){"PACKET_DNS_RECURSION"}
        fun covered(ip:String):Boolean {val literal=RouteRules.literal(ip)?:return true;val host=ClientTunCidr.parse("$ip/${literal.address.size*8}");return original.addresses.any {ClientTunCidr.parse(it,false).bits==host.bits}&&projection.peer.any {ClientTunCidr.parse(it).overlaps(host)}}
        for(endpoint in plan.remote){require(endpoint.type!="system"){"Компактный WG: DNS туннеля должен быть выбран явно, не системный выход."};require(covered(endpoint.server)){"IP-правила выводят DNS из туннеля или блокируют его."};require(controls.none {it==RouteRules.literal(endpoint.server)}){"PACKET_DNS_RECURSION"}}
    }
}
