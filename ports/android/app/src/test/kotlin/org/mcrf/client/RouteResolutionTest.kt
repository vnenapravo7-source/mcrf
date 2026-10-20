package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteResolutionTest {
    private fun base()=JSONObject("""{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"},{"type":"http","tag":"proxy","server":"127.0.0.1","server_port":1}],"route":{"rules":[],"final":"proxy"}}""")
    private fun selector(kind:String,bound:Boolean)=JSONObject().put("kind",kind).put("code","ru").put("target","direct").put("bound",bound)
    private fun rule(match:JSONObject)=JSONObject().put("id","owned").put("name","Owned").put("mode","addresses").put("text","").put("target","direct").put("enabled",true).put("nativeMatch",match)
    private fun rules(result:JSONObject)=result.getJSONObject("route").getJSONArray("rules").let {a->(0 until a.length()).map {a.getJSONObject(it)}}
    @Test fun boundGeoIpResolvesAfterEarlierDomainAndBeforeItsRuleSet() {
        val state=RouteRules.defaults().put("geoData",GeoDataConfig.defaults().put("selectors",JSONArray().put(selector("geoip",true))))
        state.getJSONArray("rules").put(rule(JSONObject().put("domain",JSONArray().put("first.fixture.test"))))
            .put(rule(JSONObject().put("rule_set",JSONArray().put("mcrf-geo-0"))).put("id","geo"))
        val compiled=rules(RouteRules.apply(base(),state,true,mapOf("geo-0" to "/fixture/geo.json")))
        val domain=compiled.indexOfFirst {it.has("domain")};val resolve=compiled.indexOfFirst {it.optString("action")=="resolve"};val geo=compiled.indexOfFirst {it.has("rule_set")}
        assertTrue(domain>=0&&resolve>domain&&geo>resolve);assertEquals(1,compiled.count {it.optString("action")=="resolve"})
    }
    @Test fun standaloneGeoIpAlsoNeedsResolveButGeoSiteDoesNot() {
        for(kind in listOf("geoip","geosite")) {
            val state=RouteRules.defaults().put("geoData",GeoDataConfig.defaults().put("selectors",JSONArray().put(selector(kind,false))))
            val compiled=rules(RouteRules.apply(base(),state,true,mapOf("geo-0" to "/fixture/geo.json")))
            assertEquals(if(kind=="geoip")1 else 0,compiled.count {it.optString("action")=="resolve"})
            if(kind=="geoip")assertTrue(compiled.indexOfFirst {it.optString("action")=="resolve"}<compiled.indexOfFirst {it.has("rule_set")})
        }
    }
    @Test fun trustedIpPresetMetadataCoversBinaryListsWithoutGuessingFromNames() {
        val id="0123456789abcdef";val tag=PresetRules.tag(id)
        val state=RouteRules.defaults();state.getJSONArray("rules").put(rule(JSONObject()).apply {remove("nativeMatch");put("presetIds",JSONArray().put(id))})
        val paths=mapOf(id to "/fixture/preset.srs")
        assertEquals(0,rules(RouteRules.apply(base(),state,true,paths)).count {it.optString("action")=="resolve"})
        assertEquals(1,rules(RouteRules.apply(base(),state,true,paths,setOf(tag))).count {it.optString("action")=="resolve"})
    }
    @Test fun nestedIpConditionResolvesOnlyOnceWithGeoAndLiteralRules() {
        val state=RouteRules.defaults().put("geoData",GeoDataConfig.defaults().put("selectors",JSONArray().put(selector("geoip",true))))
        val nested=JSONObject().put("type","logical").put("mode","or").put("rules",JSONArray().put(JSONObject().put("rule_set",JSONArray().put("mcrf-geo-0")))).put("invert",true)
        state.getJSONArray("rules").put(rule(nested)).put(rule(JSONObject().put("ip_cidr",JSONArray().put("192.0.2.0/24"))).put("id","ip"))
        assertEquals(1,rules(RouteRules.apply(base(),state,true,mapOf("geo-0" to "/fixture/geo.json"))).count {it.optString("action")=="resolve"})
    }
    @Test fun advancedDnsStrategyResolvesOnceBeforeDestinationIp() {
        for(strategy in listOf("prefer_ipv4","ipv4_only","prefer_ipv6","ipv6_only")) {
            val state=RouteRules.defaults().put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",true).put("strategy",strategy))
            state.getJSONArray("rules").put(rule(JSONObject().put("ip_cidr",JSONArray().put("192.0.2.0/24"))))
            val before=state.toString()
            val config=RouteRules.apply(base(),state,true)
            val compiled=rules(config)
            val resolves=compiled.filter {it.optString("action")=="resolve"}
            assertEquals(strategy,1,resolves.size)
            assertEquals(strategy,resolves.single().getString("strategy"))
            assertTrue(compiled.indexOf(resolves.single())<compiled.indexOfFirst {it.has("ip_cidr")})
            assertEquals(strategy,config.getJSONObject("route").getJSONObject("default_domain_resolver").getString("strategy"))
            assertEquals(before,state.toString())
        }
    }
    @Test fun importedUnconditionalStrategyResolveIsNotDuplicated() {
        val source=base()
        source.getJSONObject("route").getJSONArray("rules")
            .put(JSONObject().put("action","resolve").put("strategy","ipv4_only"))
            .put(JSONObject().put("action","route").put("ip_cidr",JSONArray().put("192.0.2.0/24")).put("outbound","direct"))
        val compiled=rules(RouteRules.apply(source,RouteRules.defaults(),true))
        assertEquals(1,compiled.count {it.optString("action")=="resolve"})
    }
    @Test fun conditionalResolveDoesNotSuppressDestinationIpGate() {
        val source=base()
        source.getJSONObject("route").getJSONArray("rules")
            .put(JSONObject().put("domain",JSONArray().put("first.fixture.test")).put("action","resolve").put("strategy","ipv4_only"))
            .put(JSONObject().put("action","route").put("ip_cidr",JSONArray().put("192.0.2.0/24")).put("outbound","direct"))
        val compiled=rules(RouteRules.apply(source,RouteRules.defaults(),true))
        assertEquals(2,compiled.count {it.optString("action")=="resolve"})
        val gate=compiled.indexOfFirst {it.has("ip_cidr")}
        assertEquals("resolve",compiled[gate-1].getString("action"))
        assertEquals(1,compiled[gate-1].length())
    }
    @Test fun nestedGeoIpIsNotCopiedIntoDnsButGeoSiteRemainsEligible() {
        for(kind in listOf("geoip","geosite")) {
            val state=RouteRules.defaults().put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",true))
                .put("geoData",GeoDataConfig.defaults().put("selectors",JSONArray().put(selector(kind,true))))
            val nested=JSONObject().put("type","logical").put("mode","or").put("rules",JSONArray()
                .put(JSONObject().put("rule_set",JSONArray().put("mcrf-geo-0")))
                .put(JSONObject().put("domain",JSONArray().put("first.fixture.test"))))
            state.getJSONArray("rules").put(rule(nested))
            val before=state.toString()
            val compiled=RouteRules.apply(base(),state,true,mapOf("geo-0" to "/fixture/geo.json"))
            assertEquals(kind,if(kind=="geoip")1 else 2,compiled.getJSONObject("dns").getJSONArray("rules").length())
            assertEquals(before,state.toString())
        }
    }
    @Test fun trustedIpPresetMetadataAlsoProtectsDnsProjection() {
        val id="0123456789abcdef"
        val state=RouteRules.defaults().put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",true))
        state.getJSONArray("rules").put(rule(JSONObject()).apply {remove("nativeMatch");put("presetIds",JSONArray().put(id))})
        val paths=mapOf(id to "/fixture/preset.srs")
        val domainConfig=RouteRules.apply(base(),state,true,paths)
        val ipConfig=RouteRules.apply(base(),state,true,paths,setOf(PresetRules.tag(id)))
        assertEquals(2,domainConfig.getJSONObject("dns").getJSONArray("rules").length())
        assertEquals(1,ipConfig.getJSONObject("dns").getJSONArray("rules").length())
        assertTrue(rules(ipConfig).any {it.has("rule_set")})
    }
}
