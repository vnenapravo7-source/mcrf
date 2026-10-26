package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TurnEmptyRoutesParityTest {
    @Test fun eachCarrierKeepsItsDefaultOutboundWithEmptyBsGroup() {
        for(kind in listOf("csqtt","wdtt","qwdtt")) {
            val entry=ServerImport.Server("owned-$kind","Fixture $kind",kind,"example.test",443,null,null,null,JSONObject().put("type",kind))
            val source=ServerImport.build(listOf(entry),entry.id,false,60,30,"Системный")
            val expected=source.getJSONObject("mcrf_carrier").getString("outbound_tag")
            val state=RouteRules.defaults()
            RoutingGroups.ensure(state);RoutingGroups.select(state,"bs")
            assertEquals(0,state.getJSONArray("rules").length())
            val tun=ProfileConfig.build(source.toString(),true,10808)
            val applied=RouteRules.apply(tun,state,true)
            assertEquals("$kind empty routes lost carrier",expected,applied.getJSONObject("route").getString("final"))
            val outputs=applied.getJSONArray("outbounds")
            assertEquals("socks",(0 until outputs.length()).map(outputs::getJSONObject).single {it.optString("tag")==expected}.getString("type"))
        }
    }
    @Test fun explicitDirectOrBlockDefaultIsNotSilentlyOverridden() {
        val source=JSONObject().put("outbounds",JSONArray()
            .put(JSONObject().put("type","direct").put("tag","direct"))
            .put(JSONObject().put("type","socks").put("tag","proxy").put("server","127.0.0.1").put("server_port",12345)))
            .put("route",JSONObject().put("final","proxy").put("rules",JSONArray()))
        val direct=RouteRules.apply(JSONObject(source.toString()),RouteRules.defaults().put("defaultTarget","direct"),true)
        assertEquals("direct",direct.getJSONObject("route").getString("final"))
        val block=RouteRules.apply(JSONObject(source.toString()),RouteRules.defaults().put("defaultTarget","block"),true)
        val rules=block.getJSONObject("route").getJSONArray("rules")
        assertEquals("reject",rules.getJSONObject(rules.length()-1).getString("action"))
    }
}
