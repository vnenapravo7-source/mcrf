package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OptionalCoreProbeTest {
    private fun config()=JSONObject().put("outbounds",JSONArray().put(JSONObject("""{"type":"vless","tag":"a","server":"fixture.example.test","server_port":443,"uuid":"4b72af9e-32b4-4b14-a2fc-4a9100d2eb21","tls":{"enabled":true}}""")))
        .put("route",JSONObject().put("final","a")).put("dns",JSONObject().put("final","dns"))
    @Test fun neededPureAndIncludesEveryOptionalKind(){val source=config();val before=source.toString();assertEquals(setOf("xray"),OptionalCoreProbe.needed(source,OptionalCoreSettings(xrayVlessDefault=true)));assertEquals(before,source.toString());assertEquals(emptySet<String>(),OptionalCoreProbe.needed(source,OptionalCoreSettings()))}
    @Test fun isolateRemovesAllUserListenersAndPeriodicAdminState(){val source=config().put("inbounds",JSONArray().put(JSONObject().put("type","tun"))).put("experimental",JSONObject().put("clash_api",JSONObject())).put("services",JSONArray().put(JSONObject().put("type","api"))).put("ntp",JSONObject().put("enabled",true));val before=source.toString();val isolated=OptionalCoreProbe.isolatedConfig(source);assertEquals(0,isolated.getJSONArray("inbounds").length());listOf("experimental","services","ntp").forEach {assertFalse(isolated.has(it))};assertEquals(source.getJSONObject("route").toString(),isolated.getJSONObject("route").toString());assertEquals(source.getJSONObject("dns").toString(),isolated.getJSONObject("dns").toString());assertEquals(before,source.toString())}
}
