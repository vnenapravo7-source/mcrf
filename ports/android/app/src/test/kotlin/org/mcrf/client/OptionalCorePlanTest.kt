package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.function.BooleanSupplier

class OptionalCorePlanTest {
    private fun source():JSONObject {
        fun output(tag:String)=JSONObject("""{"type":"vless","tag":"$tag","server":"fixture.example.test","server_port":443,"uuid":"4b72af9e-32b4-4b14-a2fc-4a9100d2eb21","tls":{"enabled":true}}""")
        return JSONObject().put("outbounds",JSONArray().put(JSONObject().put("type","selector").put("tag","select").put("outbounds",JSONArray().put("a").put("b")))
            .put(output("a")).put(JSONObject().put("type","direct").put("tag","direct")).put(output("b")))
            .put("route",JSONObject().put("final","select").put("rules",JSONArray().put(JSONObject().put("domain_suffix",JSONArray().put("fixture.test")).put("outbound","b"))))
            .put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("type","https").put("tag","dns").put("server","fixture.example.test"))))
    }
    private fun reject(run:()->Unit) {try{run();fail("accepted invalid plan")}catch(_:IllegalArgumentException){}}
    @Test fun everyOutputPlannedAndOriginalRetained(){val source=source();val before=source.toString();val p=OptionalCorePlan(source,OptionalCoreSettings(xrayVlessDefault=true));assertEquals(listOf("a","b"),p.entries().map {it.tag});val checked=mutableListOf<String>();p.preflight(BooleanSupplier {false}) {checked.add(it.tag)};assertEquals(listOf("a","b"),checked);assertEquals(before,source.toString())}
    @Test fun replacePreservesGroupsDnsAndRoutePriority(){val source=source();val p=OptionalCorePlan(source,OptionalCoreSettings(xrayVlessDefault=true));val bridges=p.entries().associate {it.tag to OptionalCoreConfig.bridgeOutbound(it.tag,1080,"fixture-user-123456","fixture-password-123456",it.request.kind)};val next=p.replace(bridges);assertEquals(source.getJSONObject("dns").toString(),next.getJSONObject("dns").toString());assertEquals(source.getJSONObject("route").toString(),next.getJSONObject("route").toString());assertEquals(source.getJSONArray("outbounds").getJSONObject(0).toString(),next.getJSONArray("outbounds").getJSONObject(0).toString());assertEquals("direct",next.getJSONArray("outbounds").getJSONObject(2).getString("type"));assertEquals("socks",next.getJSONArray("outbounds").getJSONObject(3).getString("type"));assertFalse(next.getJSONArray("outbounds").getJSONObject(1).has("network"))}
    @Test fun missingExtraAndRetaggedBridgesRejected(){val p=OptionalCorePlan(source(),OptionalCoreSettings(xrayVlessDefault=true));reject {p.replace(emptyMap())};val bridges=p.entries().associate {it.tag to OptionalCoreConfig.bridgeOutbound(it.tag,1080,"fixture-user-123456","fixture-password-123456",it.request.kind)};reject {p.replace(bridges+mapOf("foreign" to JSONObject()))};bridges["a"]!!.put("tag","wrong");reject {p.replace(bridges)}}
    @Test fun cancellationStopsPreflightBeforeNextEntry(){val p=OptionalCorePlan(source(),OptionalCoreSettings(xrayVlessDefault=true));var stop=false;var checks=0;reject {p.preflight(BooleanSupplier {stop}) {checks++;stop=true}};assertEquals(1,checks)}
    @Test fun failedPreflightStopsFollowingEntry(){val p=OptionalCorePlan(source(),OptionalCoreSettings(xrayVlessDefault=true));var checks=0;reject {p.preflight(BooleanSupplier {false}) {checks++;throw IllegalArgumentException("fixture")}};assertEquals(1,checks)}
    @Test fun callbackCannotMutatePlan(){val p=OptionalCorePlan(source(),OptionalCoreSettings(xrayVlessDefault=true));p.entries().first().request.outbound.put("protocol","wrong");assertEquals("vless",p.entries().first().request.outbound.getString("protocol"))}
    @Test fun planAppliesGlobalTransportDefaultsForAllWorkers(){val p=OptionalCorePlan(source(),OptionalCoreSettings(xrayVlessDefault=true),CoreTuningSettings(fingerprint="firefox",certificateStore="mozilla"));assertEquals(2,p.entries().size);p.entries().forEach {assertEquals("firefox",it.request.outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("fingerprint"))};assertEquals(1,p.notes.size)}
    @Test fun duplicateAndEmptyTagsRejected(){val source=source();source.getJSONArray("outbounds").getJSONObject(3).put("tag","a");reject {OptionalCorePlan(source,OptionalCoreSettings())};source.getJSONArray("outbounds").getJSONObject(3).put("tag","");reject {OptionalCorePlan(source,OptionalCoreSettings())}}
}
