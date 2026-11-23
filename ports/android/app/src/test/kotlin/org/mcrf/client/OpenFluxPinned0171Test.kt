package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class OpenFluxPinned0171Test {
    private fun profile(type:String="yandex",url:String="https://docs.example/fixture")=JSONObject()
        .put("type","openflux").put("negotiate",true).put("secret","fixture-session-secret")
        .put("context","fixture-context").put("transports",JSONArray().put(JSONObject().put("type",type).put("url",url)))
    private fun reject(block:()->Unit){assertThrows(IllegalArgumentException::class.java){block()}}
    @Test fun sourceAcceptsOnlyExactOfficial0171(){
        assertEquals("p1neappleXpress/OpenFlux",EngineUpdatePolicy.UPSTREAM)
        assertTrue(OpenFluxProfile.supportsFreshProfile("v0.17.1","f492a1a33b7581ad8f88118f97e0b866adda3cdc"))
        for(version in listOf("v0.4.2","v0.13.0","v0.16.0","v0.17.2"))assertFalse(OpenFluxProfile.supportsFreshProfile(version,OpenFluxProfile.FRESH_COMMIT))
        assertFalse(OpenFluxProfile.supportsFreshProfile("v0.17.1","0".repeat(40)))
        reject{EngineUpdatePolicy.source(JSONObject().put("repository","OpenFluxTeam/OpenFlux").put("version","v0.16.0").put("commit","5a46f2f1f178637538542a36c7f086f26df86bcd"))}
    }
    @Test fun scriptsKeepSessionSettingsAndNeverUseForeignPathsOrKeys(){
        for(id in OpenFluxProfile.OFFICIAL_SCRIPTS){
            val p=profile("script","")
            p.getJSONArray("transports").getJSONObject(0).put("params",JSONObject().put("name",id).put("settings",JSONObject().put("token","fixture")))
            val round=OpenFluxProfile.link(OpenFluxProfile.share(p)).carrier!!
            assertEquals("fixture",round.getJSONArray("transports").getJSONObject(0).getJSONObject("params").getJSONObject("settings").getString("token"))
            reject{OpenFluxProfile.fromJson("fixture",JSONObject(p.toString()).put("negotiate",false))}
        }
        val p=profile("script","")
        val params=JSONObject().put("path","/old/device/mtslink-1.0.0.flux").put("pubkey",OpenFluxProfile.OFFICIAL_SCRIPT_KEY)
        p.getJSONArray("transports").getJSONObject(0).put("params",params)
        val parsed=OpenFluxProfile.link(OpenFluxProfile.share(p)).carrier!!.getJSONArray("transports").getJSONObject(0).getJSONObject("params")
        assertEquals("mtslink",parsed.getString("name"));assertFalse(parsed.has("path"));assertFalse(parsed.has("pubkey"))
        params.put("pubkey","0".repeat(64));reject{OpenFluxProfile.fromJson("fixture",p)}
        params.put("pubkey",OpenFluxProfile.OFFICIAL_SCRIPT_KEY).put("path","/tmp/unknown.js");reject{OpenFluxProfile.fromJson("fixture",p)}
    }
    @Test fun carrierParametersAndBothRoomListVersionsSurviveEditing(){
        for(type in listOf("yandex","telemost","cupsonline","avito")){
            val urls=if(type in listOf("cupsonline","avito"))listOf("[\"a\",\"b\"]","{\"v\":2,\"rooms\":[\"a\",\"b\"]}").map {Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray())} else listOf("https://docs.example/a,https://docs.example/b")
            for(url in urls){
                val entry=OpenFluxProfile.fromJson("fixture",profile(type,url))
                val options=ConnectionEdits.snapshot(entry)
                options.getJSONObject("carrier").getJSONArray("transports").getJSONObject(0).put("priority",23).put("params",JSONObject().put("stripes","4"))
                val edited=ConnectionEdits.apply(entry,options)
                val result=OpenFluxProfile.link(ConnectionEdits.share(edited,true)).carrier!!.getJSONArray("transports").getJSONObject(0)
                assertEquals(type,result.getString("type"));assertEquals(url,result.getString("url"));assertEquals(23,result.getInt("priority"));assertEquals("4",result.getJSONObject("params").getString("stripes"))
            }
        }
    }
    @Test fun forkProtocolCannotMasqueradeAsOfficialAndErrorsRemainVisible(){
        reject{OpenFluxProfile.fromJson("fixture",profile("fluxfast").apply{getJSONArray("transports").getJSONObject(0).put("dial","host:443")})}
        val detail=CarrierFailureDetail("openflux",JSONObject())
        detail.offer("MCRF_OPENFLUX_FAILURE script_package_rejected")
        assertTrue(detail.describe("выход",1).contains("подписи"))
        assertEquals("failed protocol",ConnectionPresentation.failureDetail("Ошибка failed protocol"))
        assertEquals("native failure",ConnectionPresentation.failureDetail("Ошибка: native failure"))
    }
}
