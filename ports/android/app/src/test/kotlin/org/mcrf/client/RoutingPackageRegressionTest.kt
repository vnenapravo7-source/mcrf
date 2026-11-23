package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutingPackageRegressionTest {
    private fun rule(text:String)=JSONObject().put("id","geo-test").put("name","Geo")
        .put("text",text).put("mode","addresses").put("target","proxy").put("enabled",true)

    @Test fun textGeoCrudKeepsAnEditableSourceAcrossChangesAndDeletion() {
        val state=RouteRules.defaults().put("rules",JSONArray().put(rule("geosite:ru\ngeosite:youtube\ngeoip:ru")))
        RoutingStore.validate(state)
        TextGeoRules.compile(state)
        val editable=TextGeoRules.editable(state.getJSONArray("rules").getJSONObject(0))
        assertEquals("geosite:ru\ngeosite:youtube\ngeoip:ru",editable.getString("text"))
        assertFalse(editable.has("nativeMatch"))
        editable.put("text","geosite:youtube")
        state.put("rules",JSONArray().put(editable))
        TextGeoRules.compile(state);RoutingStore.validate(state)
        assertEquals("geosite:youtube",TextGeoRules.editable(state.getJSONArray("rules").getJSONObject(0)).getString("text"))
        state.put("rules",JSONArray());RoutingStore.validate(state)
        assertEquals(0,state.getJSONArray("rules").length())
    }
    @Test fun invalidStandardGeoIpCategoryExplainsTheCorrectPrefixWithoutMutatingInput() {
        val state=RouteRules.defaults().put("rules",JSONArray().put(rule("geoip:youtube")))
        val before=state.toString()
        val error=assertThrows(IllegalArgumentException::class.java){RoutingStore.validate(state)}
        assertTrue(error.message.orEmpty().contains("geosite:youtube"))
        assertEquals(before,state.toString())
        state.put("geoData",GeoDataConfig.defaults().put("source","custom"))
        RoutingStore.validate(state) // Custom datasets may define their own IP categories.
    }
    @Test fun simpleDirectionChangesDoNotLeakIntoTheExpertBank() {
        val root=RouteRules.defaults().put("rules",JSONArray().put(rule("geosite:ru")))
        ExperienceRouting.ensure(root);RoutingGroups.ensure(root)
        val expert=ExperienceRouting.project(root,ExperienceMode.EXPERT).toString()
        val simple=ExperienceRouting.project(root,ExperienceMode.SIMPLE)
        SimpleRoutingUi.applySelection(simple,setOf("org.example.app"),false)
        val merged=ExperienceRouting.merge(root,simple,ExperienceMode.SIMPLE)
        assertEquals(expert,ExperienceRouting.project(merged,ExperienceMode.EXPERT).toString())
        val actual=ExperienceRouting.project(merged,ExperienceMode.SIMPLE)
        assertEquals("direct",actual.getString("defaultTarget"))
        assertEquals("org.example.app",actual.getJSONArray("vpnApps").getString(0))
        assertEquals(0,actual.getJSONArray("excludedApps").length())
        SimpleRoutingUi.applySelection(actual,setOf("org.example.app"),true)
        assertEquals("proxy",actual.getString("defaultTarget"))
        assertEquals(0,actual.getJSONArray("vpnApps").length())
    }
    @Test fun perRuleAutoOptionsProduceSeparateNativePoolsAndMatchingRouteTags() {
        val first=ServerImport.read("socks5://127.0.0.1:1080").single()
        val second=ServerImport.read("socks5://127.0.0.1:1081").single()
        fun pool(id:String,seconds:Int)=rule("$id.example.test").put("id",id)
            .put(RouteServerPlan.POOL,JSONArray().put(first.id).put(second.id))
            .put(RouteServerPlan.AUTO,JSONObject().put("interval",seconds).put("tolerance",45))
        val a=pool("first",30);val b=pool("second",300)
        val state=RouteRules.defaults().put("rules",JSONArray().put(a).put(b))
        RoutingStore.validate(state)
        val base=ServerImport.build(listOf(first),first.id,false,60,50,"Cloudflare")
        val config=RouteServerPlan(state,listOf(first,second)).append(base)
        val outputs=config.getJSONArray("outbounds")
        val tags=(0 until outputs.length()).map {outputs.getJSONObject(it)}.associateBy {it.getString("tag")}
        val firstTag=RouteServerPlan.outbound(a,"main");val secondTag=RouteServerPlan.outbound(b,"main")
        assertNotEquals(firstTag,secondTag)
        assertEquals("30s",tags.getValue(firstTag).getString("interval"))
        assertEquals("300s",tags.getValue(secondTag).getString("interval"))
        assertEquals(45,tags.getValue(firstTag).getInt("tolerance"))
        a.remove(RouteServerPlan.POOL)
        assertThrows(IllegalArgumentException::class.java){RouteServerPlan.validate(a)}
    }
    @Test fun sequentialOrderNeverUsesCatalogueOrPingOrder() {
        val a=ServerImport.read("socks5://127.0.0.1:1080").single()
        val b=ServerImport.read("socks5://127.0.0.1:1081").single()
        assertEquals(listOf(b.id,a.id),AutoSelection.sequential(listOf(a,b),listOf(b.id,"deleted",a.id)).map {it.id})
    }
    @Test fun ownAutoPolicyUsesASelectorAndKeepsHealthyServerUntilFailure() {
        val a=ServerImport.read("socks5://127.0.0.1:1080").single()
        val b=ServerImport.read("socks5://127.0.0.1:1081").single()
        val r=rule("example.test").put(RouteServerPlan.POOL,JSONArray().put(a.id).put(b.id))
            .put(RouteServerPlan.AUTO,JSONObject().put("interval",15).put("tolerance",45)
                .put("mode",BsAutoSelectionPolicy.FASTEST).put("failureOnly",true).put("preferred",a.id))
        val state=RouteRules.defaults().put("rules",JSONArray().put(r));val before=state.toString()
        val prepared=RouteEditorAuto.prepare(state,listOf(a,b),JSONObject(),0)
        assertEquals(before,state.toString())
        val base=ServerImport.build(listOf(a),a.id,false,60,50,"Cloudflare")
        val config=RouteServerPlan(prepared,listOf(a,b)).append(base)
        val outbounds=config.getJSONArray("outbounds");val tag=RouteServerPlan.outbound(r,"")
        val pool=(0 until outbounds.length()).map {outbounds.getJSONObject(it)}.single {it.getString("tag")==tag}
        assertEquals("selector",pool.getString("type"))
        assertFalse(pool.has("interval")) // The existing worker owns this cadence, not native URL-test.
        var failing=false;val switches=mutableListOf<String>()
        val worker=RouteBindingSelection(prepared,listOf(a,b))
        worker.tick(1,{id->if(id==RouteServerPlan.tag(a.id)&&failing)-1 else 50},{_,id->switches.add(id)},{false})
        assertTrue(switches.isEmpty())
        failing=true
        worker.tick(15_002,{id->if(id==RouteServerPlan.tag(a.id))-1 else 50},{_,id->switches.add(id)},{false})
        assertEquals(listOf(RouteServerPlan.tag(b.id)),switches)
    }
    @Test fun sequentialRuleNeverReturnsToAnEarlierServerForBetterPing() {
        val ids=listOf("a".repeat(64),"b".repeat(64),"c".repeat(64))
        val live=mapOf(ids[0] to 1L,ids[1] to 100L,ids[2] to 50L)
        assertEquals(ids[1],RouteBindingSelection.choose(ids,ids[1],live,false,AutoSelection.SEQUENTIAL,"",0,false,emptyMap()))
        assertEquals(ids[2],RouteBindingSelection.choose(ids,ids[1],live+(ids[1] to -1L),false,AutoSelection.SEQUENTIAL,"",0,false,emptyMap()))
        assertNull(RouteBindingSelection.choose(ids,ids[2],live+(ids[2] to -1L),false,AutoSelection.SEQUENTIAL,"",0,false,emptyMap()))
    }
}
