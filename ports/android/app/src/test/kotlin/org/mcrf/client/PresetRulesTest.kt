package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PresetRulesTest {
    private val id="0123456789abcdef"
    private fun rule()=JSONObject().put("id","fixture-preset").put("name","Ready list").put("text","").put("mode","addresses").put("target","proxy").put("presetIds",JSONArray().put(id))
    private fun base()=JSONObject().put("outbounds",JSONArray().put(JSONObject().put("type","direct").put("tag","proxy")))
        .put("route",JSONObject().put("final","proxy"))
    private fun reject(action:()->Unit){try{action();fail("must reject")}catch(_:IllegalArgumentException){}}
    @Test fun compiledReferenceDoesNotExpandTheSavedProfile() {
        val row=rule();assertTrue(row.toString().length<256)
        val state=RouteRules.defaults().apply { getJSONArray("rules").put(row) }
        val config=RouteRules.apply(base(),state,false,mapOf(id to "/private/fixture.srs"))
        assertEquals("/private/fixture.srs",config.getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).getString("path"))
        assertEquals(PresetRules.tag(id),RouteRules.matches(row).getJSONObject(0).getJSONArray("rule_set").getString(0))
    }
    @Test fun manualEntriesAndPresetsAreOrAndInversionRemainsExplicit() {
        val row=rule().put("text","example.com").put("invert",true)
        val match=RouteRules.matches(row).getJSONObject(0)
        assertEquals("or",match.getString("mode"));assertTrue(match.getBoolean("invert"));assertEquals(2,match.getJSONArray("rules").length())
    }
    @Test fun unknownPathInvalidReferenceAndDuplicateAreNotSilentlyIgnored() {
        reject { PresetRules.ids(rule().put("presetIds",JSONArray().put("../../escape"))) }
        reject { PresetRules.ids(rule().put("presetIds",JSONArray().put(id).put(id))) }
        reject { PresetRules.ids(rule().put("mode","apps")) }
        reject { RouteRules.apply(base(),RouteRules.defaults().apply { getJSONArray("rules").put(rule()) },false) }
    }
    @Test fun newSpecificListsPrecedeOutsideRussiaWithoutReorderingExistingRules() {
        val state=RouteRules.defaults().apply { getJSONArray("rules").put(rule().put("id","existing"));getJSONArray("rules").put(RouteRules.outsideRussia("ru")) }
        RouteRules.insert(state,rule())
        assertEquals(listOf("existing","fixture-preset","outside-ru"),(0 until 3).map { state.getJSONArray("rules").getJSONObject(it).getString("id") })
    }
}
