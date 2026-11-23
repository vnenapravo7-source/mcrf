package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidDeployOpenFluxTest {
    private fun options(kind:String,value:String="")=JSONObject().put("create",true).put("transport",kind).put("carrier_url",value)
    private fun target(options:JSONObject)=AndroidDeployTarget("192.0.2.1",sshPassword="fixture-only",options=options)
    private fun script(name:String="mtslink",settings:Any=JSONObject().put("token","fixture;#\nvalue"))=JSONObject()
        .put("name",name).put("url","https://fixture.invalid/room").put("settings",settings).toString()
    @Test fun everyDeployTransportHasAnAcceptedOptionsShape(){
        assertEquals(setOf("direct","yandex","vyandex","boards","mailru","cupsonline","avito","telemost","script"),AndroidDeployOpenFlux.transports.toSet())
        AndroidDeployOpenFlux.transports.forEach {kind->
            val value=when(kind){"script"->script();"direct","avito","cupsonline"->"";else->"https://fixture.invalid/doc"}
            AndroidDeployPlan.validate("openflux",target(options(kind,value)))
        }
    }
    @Test fun roomTransportsAcceptEmptyOrOpaqueCodesButRejectWebsiteUrls(){
        for(kind in listOf("avito","cupsonline")){
            for(code in listOf("","WyJmaXh0dXJlLXJvb20iXQ=="))AndroidDeployPlan.validate("openflux",target(options(kind,code)))
            assertThrows(IllegalArgumentException::class.java){AndroidDeployPlan.validate("openflux",target(options(kind,"https://code.avito.ru/")))}
        }
    }
    @Test fun scriptEnvelopeRoundTripsThroughUnchangedEncryptedDraftSchema(){
        val value=script()
        val section=JSONObject().put("deployDrafts",JSONObject().put("openflux",JSONObject().put("options",options("script",value))))
        AndroidDeployBackupSettings.validate(section)
        assertEquals(value,JSONObject(section.toString()).getJSONObject("deployDrafts").getJSONObject("openflux").getJSONObject("options").getString("carrier_url"))
        assertEquals("fixture;#\nvalue",AndroidDeployOpenFlux.script(value).getJSONObject("settings").getString("token"))
    }
    @Test fun unsignedUnknownAndMalformedScriptsNeverReachInstaller(){
        for(value in listOf(script("mtslink-webrtc"),script("vkteams"),script("../../evil"),script(settings=JSONObject().put("token",4)),script(settings="{broken"),"[]",JSONObject(script()).put("path","/tmp/evil.js").toString(),JSONObject(script()).put("url","room\nPath=/tmp/evil").toString())){
            assertThrows(IllegalArgumentException::class.java){AndroidDeployPlan.validate("openflux",target(options("script",value)))}
        }
    }
    @Test fun scriptCanBeAddedToExistingPanelWithoutInstallerOrRestart(){
        val saved=JSONObject().put("panel_url","https://192.0.2.1:8443/").put("username","fixture").put("password","fixture-password")
            .put("certificate_sha256","a".repeat(64)).put("ready",true)
        val generated=AndroidDeployScripts.addTransport(AndroidDeployRecipe.all.first {it.id=="openflux"},target(options("script",script())),saved)
        for(forbidden in listOf("installer.sh","systemctl restart","git clone","openssl req"))assertFalse(generated.contains(forbidden))
        assertTrue(generated.indexOf("fingerprint != expected")<generated.indexOf("r=configure_initial"))
        assertTrue(generated.contains("mcrf_script_deploy"));assertTrue(generated.contains("--inspect-script"))
    }
}
