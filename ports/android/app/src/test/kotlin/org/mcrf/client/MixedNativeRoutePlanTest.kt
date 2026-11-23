package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MixedNativeRoutePlanTest {
    private val carrier=ServerImport.Server("owned-mixed-carrier","Carrier","sstp","carrier.test",443,
        null,null,null,JSONObject().put("type","sstp"))
    private val protocol=ServerImport.Server("owned-mixed-protocol","Protocol","trojan","protocol.test",443,
        JSONObject().put("type","trojan").put("server","protocol.test").put("server_port",443),null,null)
    private val catalogue=listOf(carrier,protocol)

    private fun rule(name:String,entry:ServerImport.Server?=null)=JSONObject().put("id",name).put("name",name)
        .put("text","$name.example.test").put("mode","addresses").put("target","proxy").put("enabled",true)
        .apply {entry?.let {put(RouteServerPlan.KEY,it.id)}}

    private fun state(vararg rules:JSONObject)=RouteRules.defaults().put("rules",JSONArray(rules.toList()))

    @Test fun nativeDispatcherRetainsFixedRuleOrderAndUsesActiveVpnForUnboundTraffic() {
        val routing=state(rule("carrier",carrier),rule("protocol",protocol),rule("unbound"))
        val before=routing.toString()
        val plan=MixedNativeRoutePlan.capture(routing,catalogue.reversed())
        assertEquals(listOf(carrier.id,protocol.id),plan.entries().map {it.id})
        val source=ProfileConfig.build(
            """{"outbounds":[{"type":"socks","tag":"active","server":"192.0.2.1","server_port":1080}],"route":{"final":"active","rules":[]}}""",
            true,10808)
        val compiled=RouteRules.apply(source,routing,true,routeServers=plan.entries(),nativeBs=true)
        val workers=plan.entries().mapIndexed {index,entry->
            BsNativeRoutingSnapshot.Egress(entry.id,12001+index,"owned-fixture-user-$index","owned-fixture-pass-$index")
        }
        val main=BsNativeRoutingSnapshot.Egress("a".repeat(64),12000,"owned-fixture-user-0","owned-fixture-pass-0")
        for(namedMain in listOf(null,main)){
            val snapshot=BsNativeRoutingSnapshot.capture(compiled,"active",emptyMap(),
                egresses=workers+listOfNotNull(namedMain),mainEgressId=namedMain?.serverId)
            val policy=snapshot.policy()
            val rules=policy.getJSONArray("rules")
            val targets=(0 until rules.length()).map {rules.getJSONObject(it)}.mapNotNull {row->
                val name=row.getJSONObject("match").optJSONArray("domain_suffix")?.optString(0)
                name?.takeIf {it.endsWith(".example.test")}?.let {it to row.getString("target")}
            }
            val fallback=namedMain?.tag?:"proxy"
            assertEquals(listOf("carrier.example.test" to workers[0].tag,"protocol.example.test" to workers[1].tag,
                "unbound.example.test" to fallback),targets)
            assertEquals(fallback,policy.getString("defaultTarget"))
            assertEquals(1,snapshot.tunInbounds().length())
        }
        assertEquals(before,routing.toString())
    }

    @Test fun unsupportedGroupsAndStaleBindingsAreRejectedAtCapture() {
        val pool=rule("pool").put(RouteServerPlan.POOL,JSONArray(listOf(carrier.id,protocol.id)))
        assertThrows(IllegalArgumentException::class.java){MixedNativeRoutePlan.capture(state(pool),catalogue)}
        val projected=state(rule("route",carrier)).put(RoutingConnectionBinding.DEFAULT,rule("default",protocol))
        assertThrows(IllegalArgumentException::class.java){MixedNativeRoutePlan.capture(projected,catalogue)}
        val linked=state(rule("route",carrier)).put(RoutingConnectionBinding.KEY,JSONObject().put("enabled",true))
        assertThrows(IllegalArgumentException::class.java){MixedNativeRoutePlan.capture(linked,catalogue)}
        assertThrows(IllegalArgumentException::class.java){MixedNativeRoutePlan.capture(state(rule("removed",protocol)),listOf(carrier))}
    }
}
