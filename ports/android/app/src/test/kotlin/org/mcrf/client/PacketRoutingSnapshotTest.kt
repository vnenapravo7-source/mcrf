package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PacketRoutingSnapshotTest {
    private fun plan(dns:String="DNS=10.8.0.1\n"):CompactWireGuardPlan.Plan {
        val key=Base64.getEncoder().encodeToString(ByteArray(32){1})
        val p=WireGuardProfile.parse("[Interface]\nPrivateKey=$key\nAddress=10.8.0.2/32,fd08::2/128\n${dns}[Peer]\nPublicKey=$key\nEndpoint=192.0.2.1:51820\nAllowedIPs=0.0.0.0/0,::/0")
        return CompactWireGuardPlan.prepare(p,"192.0.2.1")
    }
    private fun rule(id:String,text:String,target:String="direct",enabled:Boolean=true)=JSONObject().put("id",id).put("name",id).put("text",text).put("mode","any").put("target",target).put("enabled",enabled)
    @Test fun chosenNumericDnsWithoutProfileDnsIsPartOfWorkerAckSettings(){
        val original=plan("");val state=RouteRules.defaults().put("globalDns",JSONObject().put("provider","Свой").put("protocol","UDP").put("custom","10.8.0.5"))
        val before=state.toString();val captured=PacketRoutingSnapshot.capture(state,original)
        assertEquals(listOf("10.8.0.5"),captured.dns);assertEquals(captured.dns,captured.captured.dns)
        assertEquals(captured.dns,PacketRouteEnvelope.decode(captured.captured.settings,original.routes).dns)
        assertEquals(captured.routes,PacketRouteEnvelope.workerPlan(original,captured.captured.settings).routes)
        assertTrue(original.dns.isEmpty());assertEquals(before,state.toString())
    }
    @Test fun nativeIpExpressionProjectsOneOrderedEnvelopeWithoutDroppingOtherFields(){
        val match=JSONObject().put("type","logical").put("mode","or").put("rules",JSONArray()
            .put(JSONObject().put("ip_cidr","192.0.2.0/24")).put(JSONObject().put("ip_cidr","2001:db8::/32")))
        val rows=JSONArray().put(rule("keep-first","192.0.2.0/25","proxy"))
            .put(rule("native","").put("mode","addresses").put("nativeMatch",match))
        val state=RouteRules.defaults().put("rules",rows);val before=state.toString();val original=plan()
        val result=PacketRoutingSnapshot.capture(state,original)
        fun captured(ip:String)=result.routes.any {ClientTunCidr.parse(it).overlaps(ClientTunCidr.parse(ip))}
        assertTrue(captured("192.0.2.1/32"));assertFalse(captured("192.0.2.200/32"));assertFalse(captured("2001:db8::1/128"))
        assertEquals(result.captured.projection.peer,PacketRouteEnvelope.workerPlan(original,result.captured.settings).routes)
        assertEquals(before,state.toString());assertEquals(original.dns,result.dns)
        match.put("port",443)
        assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.prepare(state,original)}
    }
    @Test fun profileDnsAndDirectAppsPreserveFirstMatchWithoutWrites(){
        val state=RouteRules.defaults().put("rules",JSONArray().put(rule("first","app:org.example.kept","proxy")).put(rule("second","app:org.example.kept\napp:org.example.direct")))
        val before=state.toString();val result=PacketRoutingSnapshot.capture(state,plan())
        assertEquals(listOf("10.8.0.1"),result.dns);assertEquals(listOf("org.example.direct"),result.directApps);assertEquals(before,state.toString())
        assertThrows(UnsupportedOperationException::class.java){(result.directApps as MutableList).clear()}
    }
    @Test fun ipRulesAreProjectedWithoutLosingProfileDns(){
        val state=RouteRules.defaults().put("rules",JSONArray().put(rule("ip","192.0.2.0/24")))
        val result=PacketRoutingSnapshot.capture(state,plan());val excluded=ClientTunCidr.parse("192.0.2.1/32")
        assertFalse(result.routes.any {ClientTunCidr.parse(it).overlaps(excluded)})
        val decoded=PacketRouteEnvelope.decode(result.captured.settings,plan().routes)
        assertEquals(result.routes,decoded.projection.tun)
        assertEquals(decoded.projection.peer,PacketRouteEnvelope.workerPlan(plan(),result.captured.settings).routes)
        assertFalse(decoded.projection.peer.any {ClientTunCidr.parse(it).overlaps(excluded)})
        assertFalse(result.captured.requiresCapturedDrop)
    }
    @Test fun missingProfileDnsRequiresExplicitNumericUdp(){
        val state=RouteRules.defaults();assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.capture(state,plan(""))}
        state.put("globalDns",JSONObject().put("provider","Cloudflare").put("protocol","UDP"))
        assertEquals(listOf("1.1.1.1"),PacketRoutingSnapshot.capture(state,plan("")).dns)
    }
    @Test fun refusesDnsOutsideTunnelInsteadOfFallingBack(){
        val state=RouteRules.defaults().put("rules",JSONArray().put(rule("dns-direct","10.8.0.0/24")))
        assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.capture(state,plan())}
    }
    @Test fun domainBlockAndMixedRulesFailClosed(){
        for(rules in listOf(JSONArray().put(rule("domain","example.org")),JSONArray().put(rule("block","192.0.2.0/24","block")),
            JSONArray().put(rule("app","app:org.example.client")).put(rule("ip","192.0.2.0/24")))) {
            assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.capture(RouteRules.defaults().put("rules",rules),plan())}
        }
    }
    @Test fun disabledRulesRemainSavedButInert(){
        val state=RouteRules.defaults().put("rules",JSONArray().put(rule("disabled","example.org","block",false)))
        val before=state.toString();assertTrue(PacketRoutingSnapshot.capture(state,plan()).directApps.isEmpty());assertEquals(before,state.toString())
    }
    @Test fun refusesAdvancedDnsAndForcedApps(){
        val advanced=RouteRules.defaults().put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",true))
        assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.capture(advanced,plan())}
        val forced=RouteRules.defaults().put("vpnApps",JSONArray().put("org.example.client"))
        assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.capture(forced,plan())}
    }
    /** Queued P1 preparation checks only; no native/capability acceptance inferred. */
    @Test fun preparedDualStackBlockStaysCapturedButRuntimeAdmissionRemainsClosed(){
        val state=RouteRules.defaults().put("rules",JSONArray().put(rule("block","192.0.2.0/24\n2001:db8::/32","block")))
        val before=state.toString();val original=plan();val secret=original.secretConfiguration()
        val prepared=PacketRoutingSnapshot.prepare(state,original)
        for(cidr in listOf("192.0.2.5/32","2001:db8::5/128")){
            val address=ClientTunCidr.parse(cidr)
            assertTrue(prepared.captured.projection.tun.any {ClientTunCidr.parse(it).overlaps(address)})
            assertFalse(prepared.captured.projection.peer.any {ClientTunCidr.parse(it).overlaps(address)})
        }
        assertTrue(prepared.captured.requiresCapturedDrop)
        assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.capture(state,original)}
        assertThrows(IllegalArgumentException::class.java){PacketRouteEnvelope.workerPlan(original,prepared.captured.settings)}
        assertEquals(before,state.toString());assertEquals(secret,original.secretConfiguration())
        assertEquals("PacketRoutingPrepared(redacted)",prepared.toString())
        assertThrows(UnsupportedOperationException::class.java){(prepared.dns as MutableList).clear()}
    }
    @Test fun preparedBlockDoesNotStealEarlierDirectOrDirectDns(){
        val rules=JSONArray().put(rule("direct","192.0.2.0/25")).put(rule("block","192.0.2.0/24","block"))
        val prepared=PacketRoutingSnapshot.prepare(RouteRules.defaults().put("rules",rules),plan())
        val direct=ClientTunCidr.parse("192.0.2.1/32");val blocked=ClientTunCidr.parse("192.0.2.200/32")
        assertFalse(prepared.captured.projection.tun.any {ClientTunCidr.parse(it).overlaps(direct)})
        assertTrue(prepared.captured.projection.tun.any {ClientTunCidr.parse(it).overlaps(blocked)})
        assertFalse(prepared.captured.projection.peer.any {ClientTunCidr.parse(it).overlaps(blocked)})
        val blockedDns=RouteRules.defaults().put("rules",JSONArray().put(rule("dns-block","10.8.0.1/32","block")))
        assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.prepare(blockedDns,plan())}
    }
    @Test fun declaredBlockAdmissionCannotBypassDnsOrUnknownConditionChecks(){
        val admission=PacketRouteEnvelope.Admission.ATOMIC_IP_BLOCK
        val original=plan()
        val state=RouteRules.defaults().put("rules",JSONArray().put(rule("block","192.0.2.0/24\n2001:db8::/32","block")))
        val before=state.toString();val result=PacketRoutingSnapshot.capture(state,original,admission)
        assertTrue(result.captured.requiresCapturedDrop)
        assertEquals(listOf("10.8.0.1"),result.dns);assertEquals(before,state.toString())
        val dnsBlocked=RouteRules.defaults().put("rules",JSONArray().put(rule("dns","10.8.0.1/32","block")))
        assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.capture(dnsBlocked,original,admission)}
        val appBlocked=RouteRules.defaults().put("rules",JSONArray().put(rule("app","app:org.example.client","block")))
        assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.capture(appBlocked,original,admission)}
    }
    @Test fun preparationNeverTurnsAppBlockIntoOsBypass(){
        val state=RouteRules.defaults().put("rules",JSONArray().put(rule("app-block","app:org.example.client","block")))
        val before=state.toString()
        assertThrows(IllegalArgumentException::class.java){PacketRoutingSnapshot.prepare(state,plan())}
        assertEquals(before,state.toString())
    }
}
