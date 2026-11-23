package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerRoutingModeTest {
    private fun link()="csqtt://connect?v=2&host=fixture.invalid&peer=46000&password=fixture-only&hashes="+
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){1})
    @Test fun localChoiceSurvivesCatalogueReloadAndSubscriptionRefresh() {
        val original=ServerImport.read(link()).single()
        val catalogue=ConnectionCatalogue("")
        catalogue.replaceSubscription("https://fixture.invalid/sub",listOf(original),JSONObject(),true)
        catalogue.edit(original.id,JSONObject().put("csqttVersion","2.0").put(ServerRoutingMode.KEY,"server_first"))
        val restored=ConnectionCatalogue(catalogue.serialize())
        restored.replaceSubscription("https://fixture.invalid/sub",listOf(original),JSONObject(),true)
        val entry=restored.entries().single()
        assertEquals("server_first",entry.routingRules)
        assertEquals(original.original,entry.original)
        assertEquals("csqtt20",EngineModules.carrierKind(entry.carrier!!))
        assertEquals("server_first",ConnectionEdits.snapshot(entry).getString(ServerRoutingMode.KEY))
    }
    @Test fun newImportRequiresChoiceAndKeepsVersionSelection() {
        val plan=ImportPlan.prepare(link()){throw AssertionError("No subscription")}
        val id=plan.entries.single().id
        val version=plan.resolveCsqtt(mapOf(id to "2.0"))
        assertThrows(ServerRoutingMode.Required::class.java){version.withRoutingChoices(emptyMap(),emptySet())}
        val chosen=version.withRoutingChoices(mapOf(id to "mcrf_first"),emptySet()).entries.single()
        assertEquals("mcrf_first",chosen.routingRules)
        assertEquals("csqtt20",EngineModules.carrierKind(chosen.carrier!!))
        assertNull(version.withRoutingChoices(emptyMap(),setOf(id)).entries.single().routingRules)
    }
    @Test fun combinedModesKeepAppRulesInsideEngineWithoutMutatingSavedSettings() {
        val state=RouteRules.defaults().put("excludedApps",JSONArray().put("org.direct"))
            .put("vpnApps",JSONArray().put("org.allowed")).put("appMode","include")
        val before=state.toString()
        for(mode in listOf("mcrf_first","server_first")){
            val runtime=ServerRoutingMode.engineLocal(state,mode)
            assertEquals("all",runtime.getString("appMode"))
            assertEquals(0,RoutingAppPolicies.systemScope(runtime).direct.size)
            val rules=runtime.getJSONArray("rules")
            assertEquals(3,rules.length())
            repeat(rules.length()){assertEquals(1,RouteRules.matches(rules.getJSONObject(it)).length())}
        }
        assertEquals(before,state.toString())
        val onlyServer=ServerRoutingMode.engineLocal(state,"server")
        assertEquals(0,onlyServer.getJSONArray("rules").length())
        onlyServer.getJSONObject("globalDns").put("provider","Google")
        assertEquals(before,state.toString())
        assertEquals(before,ServerRoutingMode.engineLocal(state,"mcrf").toString())
    }
    @Test fun unknownModeIsRejected() {
        val entry=ServerImport.read(link()).single()
        assertThrows(IllegalArgumentException::class.java){ConnectionEdits.apply(entry,JSONObject().put(ServerRoutingMode.KEY,"unknown"))}
    }
}
