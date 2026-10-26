package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** New composed checks for the P3 active-group/family boundary; not a replay
 * of the accepted individual P1/P2 routing/transition suites. */
class P3TransitionFamilyParityTest {
    private fun source()=JSONObject("""{"outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":10900}],"route":{"final":"proxy","rules":[]},"inbounds":[{"type":"tun","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"]}]}""")
    private fun rule(target:String)=JSONObject().put("id",target).put("text","192.0.2.0/24\n2001:db8::/32").put("mode","addresses").put("target",target)
    @Test fun everyActionKeepsBothLiteralFamiliesAndTheTunBarrier(){
        for(target in listOf("direct","proxy","block")){
            val base=source();val before=base.toString()
            val state=RouteRules.defaults().put("rules",JSONArray().put(rule(target)))
            val result=RouteRules.apply(base,state,true)
            assertEquals(before,base.toString())
            assertEquals(base.getJSONArray("inbounds").toString(),result.getJSONArray("inbounds").toString())
            val rules=result.getJSONObject("route").getJSONArray("rules")
            val action=(0 until rules.length()).map(rules::getJSONObject).single {it.has("ip_cidr")&&it.optString("action") in setOf("route","reject")}
            val addresses=action.getJSONArray("ip_cidr")
            assertTrue((0 until addresses.length()).any {addresses.getString(it).contains(':')})
            assertTrue((0 until addresses.length()).any {addresses.getString(it).startsWith("192.0.2.")})
            if(target=="block")assertEquals("reject",action.getString("action"))else assertEquals(target,action.getString("outbound"))
        }
    }
    @Test fun emptyBsGroupDoesNotBorrowVpnIpv6BlockOrChangeDefaultOutput(){
        val state=RouteRules.defaults();RoutingGroups.ensure(state)
        state.getJSONArray("rules").put(rule("block"));RoutingGroups.select(state,"bs")
        val before=state.toString();val runtime=RoutingGroups.runtime(state)
        val result=RouteRules.apply(source(),runtime,true)
        assertEquals("proxy",result.getJSONObject("route").getString("final"))
        val rules=result.getJSONObject("route").getJSONArray("rules")
        assertFalse((0 until rules.length()).any {rules.getJSONObject(it).has("ip_cidr")})
        assertEquals(before,state.toString())
    }
    @Test fun cancelledNetworkTransitionCannotPublishEitherFamilyOrStartAnotherCore(){
        val order=mutableListOf<String>()
        assertThrows(IllegalStateException::class.java){
            PrimaryFallbackPolicy.handover({order+="retired"},{true},{order+="replacement-started"})
        }
        assertEquals(listOf("retired"),order)
        assertFalse(PrimaryFallbackPolicy.canRetryFailure(java.util.concurrent.CancellationException("owned network retired")))
    }
}
