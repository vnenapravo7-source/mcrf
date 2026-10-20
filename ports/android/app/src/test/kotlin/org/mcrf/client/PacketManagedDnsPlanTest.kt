package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Host/worker source fixtures, deliberately not a native/device capability. */
class PacketManagedDnsPlanTest {
    private val key=Base64.getEncoder().encodeToString(ByteArray(32){1})
    private fun original(dns:Boolean=false)=CompactWireGuardPlan.prepare(WireGuardProfile.parse(
        "[Interface]\nPrivateKey=$key\nAddress=10.77.0.2/32,fd77::2/128\n"+(if(dns)"DNS=10.77.0.1\n"else "")+
        "[Peer]\nPublicKey=$key\nEndpoint=192.0.2.1:51820\nAllowedIPs=0.0.0.0/0,::/0"),"192.0.2.1")
    @Test fun encryptedDefaultUsesOwnedAliasesWithoutWideningPeerOrEnablingMetadata(){
        val original=original();val state=RouteRules.defaults().put("defaultTarget","proxy");val before=state.toString()
        val prepared=PacketRoutingSnapshot.prepare(state,original)
        assertEquals(before,state.toString());assertEquals(3,JSONObject(prepared.captured.settings).getInt("schema"))
        assertEquals("https",requireNotNull(prepared.captured.managed).remote.single().type)
        val decoded=PacketRouteEnvelope.decode(prepared.captured.settings,original.routes,original.addresses)
        assertEquals(prepared.dns,decoded.dns);assertEquals(prepared.captured.settings,decoded.settings)
        for(ip in prepared.dns){
            val host=ClientTunCidr.parse("$ip/${if(':' in ip)128 else 32}")
            assertTrue(decoded.projection.tun.any {ClientTunCidr.parse(it).overlaps(host)})
            assertFalse(decoded.projection.peer.any {ClientTunCidr.parse(it).overlaps(host)})
        }
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.workerPlan(original,decoded.settings)}
        val worker=PacketRouteEnvelope.workerPlan(original,decoded.settings,managedDnsAdmission=true)
        assertEquals(decoded.projection.peer,worker.routes);assertEquals(original.addresses,worker.addresses)
        assertFalse(PacketManagedDnsPlan.admission(null))
        for(marker in listOf<Any>(true,1,"true","owned_kernel_dns_nat_v2"))assertThrows(IllegalArgumentException::class.java){PacketManagedDnsPlan.admission(marker)}
    }
    @Test fun globalIpv4IsOptInAndDoesNotEraseIpv6TunOrProfileDns(){
        val original=original(true);val state=RouteRules.defaults().put("defaultTarget","proxy")
        assertFalse(PacketManagedDnsPlan.needed(state,original,false))
        val selected=PacketRoutingSnapshot.prepare(state,original,true)
        val plan=requireNotNull(selected.captured.managed)
        assertTrue(plan.ipv4Only);assertEquals(listOf("10.77.0.1"),plan.remote.map {it.server})
        assertEquals(original.addresses,PacketRouteEnvelope.workerPlan(original,selected.captured.settings,managedDnsAdmission=true).addresses)
        assertTrue(selected.dns.any {':' in it});assertFalse(PacketManagedDnsPlan.prepare(state,original,false).ipv4Only)
        val rebound=JSONObject(selected.captured.settings);rebound.getJSONObject("dns").getJSONArray("remote").getJSONObject(0).put("server","10.77.0.4")
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.workerPlan(original,rebound.toString(),managedDnsAdmission=true)}
    }
    @Test fun unsupportedDnsOptionsAreRefusedNotSilentlyDiscarded(){
        for(option in listOf("fakeIp","optimistic")){
            val state=RouteRules.defaults().put("defaultTarget","proxy");val advanced=AdvancedDnsConfig.defaults().put("enabled",true).put(option,true);state.put("advancedDns",advanced)
            assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.prepare(state,original())}
        }
        val state=RouteRules.defaults().put("defaultTarget","proxy").put("globalDns",JSONObject().put("provider","Cloudflare").put("protocol","DoH3"))
        assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.prepare(state,original())}
    }
    @Test fun workerRejectsAliasRecursionUnknownFieldsAndMalformedNames(){
        val original=original();val plan=PacketManagedDnsPlan.prepare(RouteRules.defaults(),original,false)
        for(change in listOf<(JSONObject)->Unit>(
            {it.put("extra",true)}, {it.put("ipv4Only","true")}, {it.put("timeoutMillis",999999)},
            {it.getJSONArray("remote").getJSONObject(0).put("server","bad:host")},
            {it.getJSONObject("local").put("type","udp").put("server","recursive.invalid")},
            {it.put("profileDns",JSONArray().put(1))}
        )){val raw=plan.json();change(raw);assertThrows(IllegalArgumentException::class.java){PacketManagedDnsPlan.decode(raw)}}
        val raw=plan.json();raw.getJSONObject("direct").put("type","udp").put("server",PacketManagedDnsPlan.aliases(original).first())
        val altered=PacketManagedDnsPlan.decode(raw);val projection=PacketManagedDnsPlan.projection(PacketIpRoutes.project(original.routes,emptyList()),PacketManagedDnsPlan.aliases(original))
        assertThrows(IllegalArgumentException::class.java){PacketManagedDnsPlan.validate(altered,original,projection)}
    }
}
