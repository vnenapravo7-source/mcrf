package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Source fixtures for the next authorized verification batch. Not run by package 23–24. */
class RoutingConflictAnalyzerTest {
    private fun rule(id:String,text:String,target:String="direct",mode:String="addresses")=JSONObject()
        .put("id",id).put("name",id).put("text",text).put("mode",mode).put("target",target).put("enabled",true)
    private fun native(id:String,match:JSONObject,target:String="direct")=rule(id,"",target).put("nativeMatch",match)
    private fun state(vararg rules:JSONObject)=RouteRules.defaults().put("rules",JSONArray(rules.toList()))
    private fun data(match:JSONObject)=JSONObject().put("version",3).put("rules",JSONArray().put(match))
    private fun geo(state:JSONObject,kind:String):JSONObject=state.put("geoData",GeoDataConfig.defaults().put("selectors",JSONArray().put(
        JSONObject().put("kind",kind).put("code",if(kind=="geosite")"category-ru"else"ru").put("target","proxy").put("managedRule",true))))

    @Test fun suffixShadowsExactSubdomainInOrder() {
        val report=RoutingConflictAnalyzer.inspect(state(rule("a","example.org"),rule("b","DOMAIN,www.example.org","proxy")))
        val finding=report.findings.single()
        assertTrue(finding.exact);assertTrue(finding.shadowed);assertFalse(finding.sameOutput)
        assertEquals("a",finding.earlierId);assertEquals(2,finding.laterPosition)
    }
    @Test fun ipv4AndIpv6Containment() {
        for((wide,narrow)in listOf("10.0.0.0/8" to "10.5.0.0/16","2001:db8::/32" to "2001:db8:1::/48")) {
            val finding=RoutingConflictAnalyzer.inspect(state(rule("a",wide),rule("b",narrow,"proxy"))).findings.single()
            assertTrue(finding.exact);assertTrue(finding.shadowed)
        }
    }
    @Test fun differentAppsAreDisjoint() {
        assertTrue(RoutingConflictAnalyzer.inspect(state(rule("a","app:org.example.one",mode="apps"),rule("b","app:org.example.two",mode="apps"))).findings.isEmpty())
    }
    @Test fun inAppsAndExceptSameAppsAreDisjoint() {
        val list="example.org\napp:org.example.one"
        assertTrue(RoutingConflictAnalyzer.inspect(state(rule("a",list,mode="in-apps"),rule("b",list,mode="except-apps"))).findings.isEmpty())
    }
    @Test fun nativeDomainAndIpAreAlternatives() {
        val combined=native("a",JSONObject().put("domain",JSONArray().put("example.org")).put("ip_cidr",JSONArray().put("10.0.0.0/8")))
        val finding=RoutingConflictAnalyzer.inspect(state(combined,rule("b","10.3.0.0/16","proxy"))).findings.single()
        assertTrue(finding.exact);assertTrue(finding.shadowed)
    }
    @Test fun uncachedGeositeStaysPossible() {
        val source=geo(state(native("a",JSONObject().put("rule_set",JSONArray().put("mcrf-geo-0"))),rule("b","example.org","proxy")),"geosite")
        val finding=RoutingConflictAnalyzer.inspect(source).findings.single()
        assertFalse(finding.exact);assertFalse(finding.shadowed);assertTrue(finding.explanation.contains("geosite:category-ru"))
    }
    @Test fun cachedGeositeAndDomainOverlap() {
        val source=geo(state(native("a",JSONObject().put("rule_set",JSONArray().put("mcrf-geo-0"))),rule("b","DOMAIN,www.example.ru","proxy")),"geosite")
        val finding=RoutingConflictAnalyzer.inspect(source,{data(JSONObject().put("domain_suffix",JSONArray().put("example.ru")))}).findings.single()
        assertTrue(finding.exact);assertTrue(finding.shadowed)
    }
    @Test fun cachedGeoipAndIpOverlap() {
        val source=geo(state(native("a",JSONObject().put("rule_set",JSONArray().put("mcrf-geo-0"))),rule("b","10.3.0.0/16","proxy")),"geoip")
        val finding=RoutingConflictAnalyzer.inspect(source,{data(JSONObject().put("ip_cidr",JSONArray().put("10.0.0.0/8")))}).findings.single()
        assertTrue(finding.exact);assertTrue(finding.shadowed)
    }
    @Test fun inverseDomainDoesNotOverlapExcludedSuffix() {
        val inverse=rule("a","example.org","proxy").put("invert",true)
        assertTrue(RoutingConflictAnalyzer.inspect(state(inverse,rule("b","www.example.org"))).findings.isEmpty())
    }
    @Test fun unresolvedSourceIpFlagsCannotProveDisjointness() {
        val custom=JSONObject().put("rule_set",JSONArray().put("mcrf-geo-0")).put("rule_set_ip_cidr_match_source",true)
        val logic=RoutingConflictLogic({data(JSONObject().put("ip_cidr",JSONArray().put("10.0.0.0/8")))},{"geoip:ru"})
        val match=logic.solve(logic.both(logic.parse(custom),logic.parse(JSONObject().put("ip_cidr",JSONArray().put("203.0.113.0/24")))))
        assertEquals(RoutingConflictLogic.Kind.POSSIBLE,match.kind)
    }
    @Test fun noChangeIsNoOpAndResolutionOnlyDisablesOneRow() {
        val source=state(rule("a","example.org"),rule("b","example.org","proxy"));val before=source.toString()
        val report=RoutingConflictAnalyzer.inspect(source)
        assertEquals(before,RoutingConflictAnalyzer.resolve(source,report,null,emptySet()).toString())
        val resolved=RoutingConflictAnalyzer.resolve(source,report,"b",setOf("a")).getJSONArray("rules")
        assertEquals(2,resolved.length());assertEquals("a",resolved.getJSONObject(0).getString("id"))
        assertFalse(resolved.getJSONObject(0).getBoolean("enabled"));assertTrue(resolved.getJSONObject(1).getBoolean("enabled"))
        assertEquals(before,source.toString())
    }
}
