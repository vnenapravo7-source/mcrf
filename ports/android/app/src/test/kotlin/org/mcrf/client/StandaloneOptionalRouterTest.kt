package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StandaloneOptionalRouterTest {
    private fun vless(tag:String)=JSONObject(
        """{"type":"vless","tag":"$tag","server":"fixture.example.test","server_port":443,"uuid":"4b72af9e-32b4-4b14-a2fc-4a9100d2eb21","tls":{"enabled":true}}"""
    )
    private fun source(tag:String,vararg outputs:JSONObject)=JSONObject()
        .put("outbounds",JSONArray(outputs.toList()))
        .put("route",JSONObject().put("final",tag))

    @Test fun choosesOnlyExplicitTerminalXrayAndCarriesCoreTuning() {
        val config=source("selected",JSONObject().put("type","direct").put("tag","direct"),vless("selected"))
        val saved=config.toString()
        val request=checkNotNull(StandaloneOptionalRouter.request(config,"selected",
            OptionalCoreSettings(xrayVlessDefault=true),CoreTuningSettings(fingerprint="firefox")))
        assertEquals("xray",request.kind)
        assertEquals("firefox",request.outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("fingerprint"))
        assertEquals(saved,config.toString())
        assertNull(StandaloneOptionalRouter.request(config,"direct",OptionalCoreSettings(xrayVlessDefault=true),CoreTuningSettings()))
    }

    @Test fun choosesTlsCoreOnlyForRequestedTlsFeatures() {
        val output=JSONObject("""{"type":"http","tag":"selected","server":"fixture.example.test","server_port":443,"tls":{"enabled":true}}""")
        val config=source("selected",output)
        assertNull(StandaloneOptionalRouter.request(config,"selected",OptionalCoreSettings(),CoreTuningSettings()))
        val request=checkNotNull(StandaloneOptionalRouter.request(config,"selected",
            OptionalCoreSettings(customFragmentDefault=true),CoreTuningSettings()))
        assertEquals("tls-core",request.kind)
        assertTrue(request.outbound.getJSONObject("tls_fragment").getBoolean("enabled"))
    }

    @Test fun multipleOptionalWorkersAndCarrierSourcesStayOutsideSingleEgress() {
        val config=source("first",vless("first"),vless("second"))
        val settings=OptionalCoreSettings(xrayVlessDefault=true)
        assertThrows(IllegalArgumentException::class.java) {
            StandaloneOptionalRouter.request(config,"first",settings,CoreTuningSettings())
        }
        config.put("mcrf_carrier",JSONObject())
        assertNull(StandaloneOptionalRouter.request(config,"first",settings,CoreTuningSettings()))
    }
}
