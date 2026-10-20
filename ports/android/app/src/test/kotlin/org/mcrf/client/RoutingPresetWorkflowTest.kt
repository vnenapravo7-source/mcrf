package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutingPresetWorkflowTest {
    private fun state()=RouteRules.defaults()
    private fun base()=JSONObject("""{"route":{"rules":[],"final":"proxy"},"outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1080}],"inbounds":[]}""")
    private fun google()=GeoDataPresets.items.single {it.kind=="geosite"&&it.code=="google"}
    @Test fun geoCatalogueHasRussiaOnlyAndOverFiftyDomainOptions(){
        assertEquals(listOf("ru"),GeoDataPresets.items.filter {it.kind=="geoip"}.map {it.code})
        assertTrue(GeoDataPresets.items.count {it.kind=="geosite"}>=50)
        assertEquals(GeoDataPresets.items.size,GeoDataPresets.items.distinctBy {it.kind+":"+it.code}.size)
    }
    @Test fun searchCrossesTypeBoundaryButEmptyQueryRespectsDropdown(){
        assertTrue(RoutingPresetSearch.includes("GOOGLE",true,0,"Google geosite:google"))
        assertTrue(RoutingPresetSearch.includes("google",false,1,"Google сервис"))
        assertFalse(RoutingPresetSearch.includes("",true,0,"Google"))
        assertTrue(RoutingPresetSearch.includes("",true,1,"Google"))
        assertFalse(RoutingPresetSearch.includes("google microsoft",true,0,"Google"))
    }
    @Test fun geoChoiceCreatesVisibleRuleAndRepeatedChoiceUpdatesWithoutDuplicates(){
        val s=state();GeoDataPresets.addRules(s,listOf(google()),"direct");RoutingStore.validate(s)
        assertEquals(1,s.getJSONArray("rules").length());assertEquals("direct",s.getJSONArray("rules").getJSONObject(0).getString("target"))
        GeoDataPresets.addRules(s,listOf(google()),"block");RoutingStore.validate(s)
        assertEquals(1,s.getJSONArray("rules").length());assertEquals(1,GeoDataConfig.value(s).getJSONArray("selectors").length())
        assertEquals("block",s.getJSONArray("rules").getJSONObject(0).getString("target"))
    }
    @Test fun disabledAndDeletedGeoRulesDoNotHaveHiddenRoutingFallback(){
        val s=state();GeoDataPresets.addRules(s,listOf(google()),"proxy")
        s.getJSONArray("rules").getJSONObject(0).put("enabled",false)
        for(deleted in listOf(false,true)){
            if(deleted)s.put("rules",JSONArray())
            assertTrue(GeoDataConfig.neededSelectors(s).isEmpty())
            val c=base();GeoDataConfig.attach(c,s,mapOf("geo-0" to "/fixture/google.json"),"proxy","direct")
            assertEquals(0,c.getJSONObject("route").getJSONArray("rules").length())
        }
    }
    @Test fun visibleGeoRuleCompilesExactlyOnceAndBlockUsesReject(){
        val s=state();GeoDataPresets.addRules(s,listOf(google()),"block")
        val c=RouteRules.apply(base(),s,true,mapOf("geo-0" to "/fixture/google.json"))
        val matches=c.getJSONObject("route").getJSONArray("rules")
        val geoRules=(0 until matches.length()).map {matches.getJSONObject(it)}.filter {it.optJSONArray("rule_set")?.toString()?.contains("mcrf-geo-0")==true}
        assertEquals(1,geoRules.size);assertEquals("reject",geoRules.single().getString("action"))
    }
    @Test fun emptyGeoSelectionDoesNotTouchSettings(){val s=state();val before=s.toString();GeoDataPresets.addRules(s,emptyList(),"direct");assertEquals(before,s.toString())}
    @Test fun addingGeoKeepsImportedBindingsAndSource(){
        val original=GeoDataConfig.fromProvider(JSONObject("""{"ProxySites":["geosite:yandex"]}"""))
        val s=state().put("geoData",original);val bindings=original.getJSONArray("providerRules").toString()
        GeoDataPresets.addRules(s,listOf(google()),"direct");RoutingStore.validate(s)
        assertEquals(bindings,GeoDataConfig.value(s).getJSONArray("providerRules").toString())
        assertEquals("mcrf-geo-1",s.getJSONArray("rules").getJSONObject(0).getJSONObject("nativeMatch").getJSONArray("rule_set").getString(0))
    }
    @Test fun geoPresetCannotAddChinaIp(){assertThrows(IllegalArgumentException::class.java){GeoDataPresets.addRules(state(),listOf(GeoDataPresets.Item("geoip","cn","China","proxy")),"proxy")}}
    @Test fun serviceButtonsCreateAndRetargetVisibleRuleWithoutDuplicates(){
        val item=PresetAssets.Item("1234567890abcdef","Google","Домены",1,1,0,1,"0".repeat(64),"fixture")
        for(target in listOf("direct","proxy","block")){
            val s=state();RoutingDefaults.addSelected(s,listOf(item),setOf(item.id),target=target);RoutingStore.validate(s)
            assertEquals(target,s.getJSONArray("rules").getJSONObject(0).getString("target"))
            RoutingDefaults.addSelected(s,listOf(item),setOf(item.id),target="block")
            assertEquals(1,s.getJSONArray("rules").length())
        }
    }
}
