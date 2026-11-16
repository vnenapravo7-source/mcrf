package org.mcrf.client

import org.json.JSONObject
import java.net.URI

/** Admission of an owned, narrow DEBUG packet fixture, never a user profile fallback. */
object ClientPacketFixture {
    internal val encryptedModes:Set<String> = java.util.Collections.unmodifiableSet(setOf("tls-vpn","https-vpn","tls-direct","https-direct","tls-bad-name","https-bad-name","tls-untrusted","https-untrusted"))
    // Android res_send.cpp discards SERVFAIL, retries its selected aliases and
    // reports ERROR_SYSTEM/ETIMEDOUT. This is NOT a successful DNS response.
    // Accepted only by this negative DEBUG fixture after its eight real wire
    // SERVFAIL assertions; independent TLS/no-fallback + permitted-after remain.
    internal fun frameworkRejection(expectedRcode:Int,errorCode:Int,errno:Int):Boolean = expectedRcode==2&&errorCode==1&&errno==110
    class Result internal constructor(val original: String, val dial: String, val apkHash: String,
                                     val body: String, val path: String, val plan: CompactWireGuardPlan.Plan,val captureBlocked:Boolean,
                                     val managedDns:String?=null,val ipv4Only:Boolean=false) {
        val encryptedDns:Boolean get()=managedDns in encryptedModes
        val dnsRcode:Int get()=if(managedDns?.endsWith("bad-name")==true||managedDns?.endsWith("untrusted")==true)2 else if(encryptedDns)3 else 0
        val captured:PacketRouteEnvelope.Captured = PacketRouteEnvelope.capture(plan.routes,if(captureBlocked)
            listOf(PacketIpRoutes.Rule(listOf("10.77.0.3/32","fd77::3/128"),PacketIpRoutes.Target.BLOCK))else emptyList()).let {base->
                if(managedDns==null){if(captureBlocked)base else PacketRouteEnvelope.withDns(base,plan.dns)} else {
                    val host=if(encryptedDns||managedDns.endsWith('4'))"10.77.0.1"else"fd77::1"
                    val state=RouteRules.defaults().put("globalDns",JSONObject().put("provider","Свой").put("protocol","UDP").put("custom",host))
                    val raw=PacketManagedDnsPlan.prepare(state,plan,ipv4Only).json().put("cacheCapacity",0)
                    if(managedDns.startsWith("tcp"))raw.getJSONArray("remote").getJSONObject(0).put("type","tcp")
                    if(encryptedDns){
                        val direct=managedDns.endsWith("direct")||managedDns.endsWith("bad-name")
                        val https=managedDns.startsWith("https-")
                        val untrusted=managedDns.endsWith("untrusted")
                        val endpoint=JSONObject().put("type",if(https)"https"else"tls")
                            .put("server",if(direct)"1.1.1.1"else"10.77.0.1")
                            .put("port",if(direct){if(https)443 else 853}else if(untrusted){if(https)1443 else 1853}else{if(https)2443 else 2853})
                            .put("path",if(https)"/dns-query"else"")
                            .put("tlsName",if(untrusted)"owned.mcrf.invalid"else if(managedDns.endsWith("bad-name"))"wrong-owned.invalid"else"one.one.one.one")
                        raw.put("finalDirect",direct).put("timeoutMillis",10000)
                        if(direct)raw.put("direct",endpoint)else raw.getJSONArray("remote").put(0,endpoint)
                    }
                    val dns=PacketManagedDnsPlan.decode(raw)
                    PacketRouteEnvelope.withManagedDns(base,dns,PacketManagedDnsPlan.aliases(plan)).also {PacketManagedDnsPlan.validate(dns,plan,it.projection)}
                }
            }
        val dns:List<String> = captured.dns?:plan.dns
        val tunRoutes:List<String> = captured.projection.tun
        val peerRoutes:List<String> = captured.projection.peer
    }
    fun parse(source: JSONObject, healthUrl: String): Result {
        require(source.keys().asSequence().toSet() == setOf("mcrf_packet_fixture")) { "PACKET_FIXTURE_ENVELOPE" }
        val fixture = source.getJSONObject("mcrf_packet_fixture")
        val keys=fixture.keys().asSequence().toSet()
        val base=setOf("original","dialIpv4","apkSha256","expectedBody")
        val managed=fixture.opt("managedDns") as? String
        require(keys == base || keys == base+"captureBlocked" || managed in setOf("udp4","udp6","tcp4","tcp6")+encryptedModes&&
            (keys==base+"managedDns"||keys==base+setOf("managedDns","ipv4Compatibility"))) { "PACKET_FIXTURE_FIELDS" }
        require(managed !in encryptedModes||!fixture.has("ipv4Compatibility")){"PACKET_FIXTURE_ENCRYPTED_IPV4_FLAG"}
        require(!fixture.has("ipv4Compatibility")||fixture.opt("ipv4Compatibility")==true){"PACKET_FIXTURE_IPV4_FLAG"}
        require(!fixture.has("captureBlocked")||fixture.opt("captureBlocked")==true) {"PACKET_FIXTURE_BLOCK_FLAG"}
        val dial = fixture.getString("dialIpv4")
        val parts = dial.split('.')
        require(parts.size == 4 && parts.all {it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255}) { "PACKET_FIXTURE_IP" }
        val n = parts.map(String::toInt)
        require(n[0] == 10 || n[0] == 172 && n[1] in 16..31 || n[0] == 192 && n[1] == 168) { "PACKET_FIXTURE_PRIVATE_PEER" }
        val hash = fixture.getString("apkSha256")
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "PACKET_FIXTURE_PIN" }
        val body = fixture.getString("expectedBody")
        require(body.matches(Regex("mcrf-fixture-[0-9a-f]{32}"))) { "PACKET_FIXTURE_NONCE" }
        val path = "/fixture/"+body.removePrefix("mcrf-fixture-")
        require(healthUrl == "http://10.77.0.1:8080$path") { "PACKET_FIXTURE_HEALTH" }
        val original = fixture.getString("original")
        val profile = WireGuardProfile.parse(original)
        val endpoint = URI("udp://${profile.fields().getValue("Endpoint")}")
        require(endpoint.host == dial) { "PACKET_FIXTURE_ENDPOINT" }
        val plan = CompactWireGuardPlan.prepare(profile,dial)
        val blocked=fixture.optBoolean("captureBlocked",false)
        val expectedRoutes=if(blocked)listOf("10.77.0.1/32","10.77.0.3/32","fd77::1/128","fd77::3/128")else listOf("10.77.0.1/32","fd77::1/128")
        require(plan.mtu == 1280 && plan.addresses == listOf("10.77.0.2/32","fd77::2/128") &&
            plan.routes == expectedRoutes && plan.dns==(if(managed==null)listOf("10.77.0.1")else emptyList<String>())) { "PACKET_FIXTURE_BOUNDED_TUN" }
        return Result(original,dial,hash,body,path,plan,blocked,managed,fixture.optBoolean("ipv4Compatibility",false))
    }
}
