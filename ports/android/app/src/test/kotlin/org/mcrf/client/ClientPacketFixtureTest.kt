package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ClientPacketFixtureTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) {(it+1).toByte()})
    private val body = "mcrf-fixture-"+"a".repeat(32)
    private val url = "http://10.77.0.1:8080/fixture/"+"a".repeat(32)
    private fun source() = JSONObject().put("mcrf_packet_fixture",JSONObject()
        .put("original","[Interface]\nPrivateKey=$key\nAddress=10.77.0.2/32,fd77::2/128\nDNS=10.77.0.1\nMTU=1280\n[Peer]\nPublicKey=$key\nEndpoint=192.168.7.1:51820\nAllowedIPs=10.77.0.1/32,fd77::1/128")
        .put("dialIpv4","192.168.7.1").put("apkSha256","b".repeat(64)).put("expectedBody",body))
    @Test fun frameworkServfailIsOnlyAnExactNegativeOutcomeNeverPositiveDns(){
        assertTrue(ClientPacketFixture.frameworkRejection(2,1,110))
        for(rcode in listOf(0,1,3,5))assertFalse(ClientPacketFixture.frameworkRejection(rcode,1,110))
        for(code in listOf(-1,0,2))assertFalse(ClientPacketFixture.frameworkRejection(2,code,110))
        for(errno in listOf(-1,0,5,13,22,111))assertFalse(ClientPacketFixture.frameworkRejection(2,1,errno))
    }
    @Test fun encryptedFixtureUsesOnlyExactTlsEndpointsWithSystemTrustAndNoProfileChanges(){
        for(mode in ClientPacketFixture.encryptedModes){
            val input=source();val value=input.getJSONObject("mcrf_packet_fixture")
            value.put("managedDns",mode).put("original",value.getString("original").replace("DNS=10.77.0.1\n",""))
            val parsed=ClientPacketFixture.parse(input,url);val plan=requireNotNull(parsed.captured.managed)
            val direct=mode.endsWith("direct")||mode.endsWith("bad-name");val https=mode.startsWith("https-");val untrusted=mode.endsWith("untrusted")
            val endpoint=if(direct)plan.direct else plan.remote.single()
            assertTrue(parsed.encryptedDns);assertTrue(parsed.plan.dns.isEmpty());assertFalse(plan.ipv4Only)
            assertEquals(direct,plan.finalDirect);assertEquals(0,plan.cacheCapacity);assertEquals(10000,plan.timeoutMillis)
            assertEquals(if(https)"https"else"tls",endpoint.type)
            assertEquals(if(direct)"1.1.1.1"else"10.77.0.1",endpoint.server)
            assertEquals(if(direct){if(https)443 else 853}else if(untrusted){if(https)1443 else 1853}else{if(https)2443 else 2853},endpoint.port)
            assertEquals(if(untrusted)"owned.mcrf.invalid"else if(mode.endsWith("bad-name"))"wrong-owned.invalid"else"one.one.one.one",endpoint.tlsName)
            assertEquals(if(https)"/dns-query"else"",endpoint.path)
            assertEquals(if(untrusted||mode.endsWith("bad-name"))2 else 3,parsed.dnsRcode)
            assertEquals(2,parsed.dns.size);assertEquals(parsed.peerRoutes,PacketRouteEnvelope.workerPlan(parsed.plan,parsed.captured.settings,managedDnsAdmission=true).routes)
            value.put("ipv4Compatibility",true)
            assertThrows(IllegalArgumentException::class.java){ClientPacketFixture.parse(input,url)}
        }
    }
    @Test fun managedFixturePinsOnlyOwnedNumericUpstreamAndDoesNotReuseLegacyDnsPath(){
        for(mode in listOf("udp4","udp6","tcp4","tcp6"))for(ipv4Only in listOf(false,true)){
            val input=source();val value=input.getJSONObject("mcrf_packet_fixture")
            value.put("managedDns",mode).put("original",value.getString("original").replace("DNS=10.77.0.1\n",""))
            if(ipv4Only)value.put("ipv4Compatibility",true)
            val captured=ClientPacketFixture.parse(input,url)
            assertTrue(captured.plan.dns.isEmpty());assertEquals(2,captured.dns.size);assertEquals(mode,captured.managedDns)
            val plan=requireNotNull(captured.captured.managed)
            assertEquals(if(mode.startsWith("tcp"))"tcp"else"udp",plan.remote.single().type)
            assertEquals(if(mode.endsWith('4'))"10.77.0.1"else"fd77::1",plan.remote.single().server)
            assertEquals(ipv4Only,plan.ipv4Only);assertEquals(0,plan.cacheCapacity)
            assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.workerPlan(captured.plan,captured.captured.settings)}
            assertEquals(captured.peerRoutes,PacketRouteEnvelope.workerPlan(captured.plan,captured.captured.settings,managedDnsAdmission=true).routes)
        }
    }
    @Test fun managedFixtureCannotDiscardOriginalDnsOrAddArbitraryTransportsAndFlags(){
        for(mode in listOf<Any>(true,1,"udp","https","tcp5")){
            val input=source();input.getJSONObject("mcrf_packet_fixture").put("managedDns",mode)
            assertThrows(IllegalArgumentException::class.java){ClientPacketFixture.parse(input,url)}
        }
        val input=source();input.getJSONObject("mcrf_packet_fixture").put("managedDns","udp4")
        assertThrows(IllegalArgumentException::class.java){ClientPacketFixture.parse(input,url)}
        input.getJSONObject("mcrf_packet_fixture").put("captureBlocked",true)
        assertThrows(IllegalArgumentException::class.java){ClientPacketFixture.parse(input,url)}
    }
    @Test fun blockFixtureCarriesAtomicProjectionAndNarrowsOriginalPeer(){
        val input=source();val fixture=input.getJSONObject("mcrf_packet_fixture")
        fixture.put("captureBlocked",true).put("original",fixture.getString("original").replace(
            "AllowedIPs=10.77.0.1/32,fd77::1/128","AllowedIPs=10.77.0.1/32,10.77.0.3/32,fd77::1/128,fd77::3/128"))
        val parsed=ClientPacketFixture.parse(input,url)
        assertTrue(parsed.captureBlocked)
        assertEquals(parsed.plan.routes.map {ClientTunCidr.parse(it)},parsed.tunRoutes.map {ClientTunCidr.parse(it)})
        assertEquals(listOf("10.77.0.1/32","fd77::1/128").map {ClientTunCidr.parse(it)},parsed.peerRoutes.map {ClientTunCidr.parse(it)})
        val admitted=PacketRouteEnvelope.ownedBlockFixturePlan(parsed.plan,parsed.captured.settings)
        assertEquals(parsed.peerRoutes,admitted.routes)
        assertFalse(admitted.secretConfiguration().contains("allowed_ip=10.77.0.3/32"))
        assertFalse(admitted.secretConfiguration().contains("allowed_ip=fd77::3/128"))
        assertTrue(parsed.plan.secretConfiguration().contains("allowed_ip=10.77.0.3/32"))
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.workerPlan(parsed.plan,parsed.captured.settings)}
        assertThrows(UnsupportedOperationException::class.java){(parsed.tunRoutes as MutableList).clear()}
        for(value in listOf<Any>(false,1,"true")){
            input.getJSONObject("mcrf_packet_fixture").put("captureBlocked",value)
            assertThrows(IllegalArgumentException::class.java){ClientPacketFixture.parse(input,url)}
        }
    }
    @Test fun blockFixtureCannotReuseLegacyManualTunRouteSetupOrArbitraryRules(){
        val input=source();input.getJSONObject("mcrf_packet_fixture").put("captureBlocked",true)
        assertThrows(IllegalArgumentException::class.java){ClientPacketFixture.parse(input,url)}
        val ordinary=ClientPacketFixture.parse(source(),url)
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.ownedBlockFixturePlan(ordinary.plan,ordinary.captured.settings)}
        val wide=CompactWireGuardPlan.prepare(WireGuardProfile.parse(ordinary.original.replace(
            "AllowedIPs=10.77.0.1/32,fd77::1/128","AllowedIPs=10.77.0.1/32,10.77.0.3/32,fd77::1/128,fd77::3/128")),ordinary.dial)
        for(target in listOf(PacketIpRoutes.Target.DIRECT,PacketIpRoutes.Target.VPN)) {
            val settings=PacketRouteEnvelope.capture(wide.routes,listOf(PacketIpRoutes.Rule(listOf("10.77.0.3/32","fd77::3/128"),target))).settings
            assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.ownedBlockFixturePlan(wide,settings)}
        }
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.ownedBlockFixturePlan(wide,"{}")}
    }
    @Test fun acceptsOnlyNarrowDualStackPeerWithoutRetainingMutableJson() {
        val input=source();val parsed=ClientPacketFixture.parse(input,url)
        input.getJSONObject("mcrf_packet_fixture").put("original","changed")
        assertEquals(listOf("10.77.0.1/32","fd77::1/128"),parsed.plan.routes)
        assertEquals(listOf("10.77.0.1"),parsed.plan.dns);assertTrue(parsed.original.contains("[Interface]"))
        assertFalse(parsed.toString().contains(key))
    }
    @Test fun rejectsGlobalRouteDnsWrongMtuOrDifferentEndpoint() {
        listOf("10.77.0.1/32" to "0.0.0.0/0","MTU=1280" to "MTU=1500", "DNS=10.77.0.1" to "DNS=1.1.1.1", "DNS=10.77.0.1\n" to "", "192.168.7.1:51820" to "192.168.7.2:51820").forEach {(from,to)->
            val input=source();val nested=input.getJSONObject("mcrf_packet_fixture")
            nested.put("original",nested.getString("original").replace(from,to))
            assertThrows(IllegalArgumentException::class.java) {ClientPacketFixture.parse(input,url)}
        }
    }
    @Test fun rejectsPublicPeerInvalidNonceAndExtraEnvelope() {
        listOf("dialIpv4" to "8.8.8.8","dialIpv4" to "192.168.007.1","apkSha256" to "short","expectedBody" to "not-fresh").forEach {(field,value)->
            val input=source();input.getJSONObject("mcrf_packet_fixture").put(field,value)
            assertThrows(IllegalArgumentException::class.java) {ClientPacketFixture.parse(input,url)}
        }
        assertThrows(IllegalArgumentException::class.java) {ClientPacketFixture.parse(source().put("extra",true),url)}
    }
    @Test fun rejectsHealthRedirectsOtherTargetsAndNonceMismatch() {
        listOf(url.replace("10.77.0.1","example.org"),"$url?x=1",url.replace("8080","80"),url.dropLast(1)+"b").forEach {
            assertThrows(IllegalArgumentException::class.java) {ClientPacketFixture.parse(source(),it)}
        }
    }
}
