package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteServerAddressFamilyTest {
    private val id="a".repeat(64)
    @Test fun logicalOrCannotTurnOneServersCompatibilityIntoAQueryTypeWideFilter() {
        val config=source();val dns=config.getJSONObject("dns")
        val match=JSONObject().put("type","logical").put("mode","or").put("server","mcrf-dns-route-$id")
            .put("rules",JSONArray().put(JSONObject().put("domain_suffix",JSONArray().put("owned.test")))
                .put(JSONObject().put("query_type",JSONArray().put("AAAA"))))
        dns.getJSONArray("rules").put(match)
        val result=RouteServerAddressFamily.apply(config,"primary",false,setOf(id))
        val rules=result.getJSONObject("dns").getJSONArray("rules")
        assertFalse(rules.getJSONObject(rules.length()-1).has("strategy"))
        assertEquals(match.toString(),rules.getJSONObject(rules.length()-1).toString())
    }
    @Test fun logicalAndCanScopeTheParentButNeverWritesStrategyIntoMatchers() {
        val config=source();val children=JSONArray().put(JSONObject().put("domain_suffix",JSONArray().put("owned.test")))
            .put(JSONObject().put("query_type",JSONArray().put("A").put("AAAA")))
        val before=children.toString()
        config.getJSONObject("dns").getJSONArray("rules").put(JSONObject().put("type","logical").put("mode","and")
            .put("server","mcrf-dns-route-$id").put("rules",children))
        val rules=RouteServerAddressFamily.apply(config,"primary",false,setOf(id)).getJSONObject("dns").getJSONArray("rules")
        val action=rules.getJSONObject(rules.length()-1)
        assertEquals("ipv4_only",action.getString("strategy"));assertEquals(before,action.getJSONArray("rules").toString())
    }
    private fun source():JSONObject=JSONObject().put("dns",JSONObject().put("strategy","prefer_ipv4")
        .put("servers",JSONArray().put(JSONObject().put("tag","mcrf-dns-remote").put("detour","primary"))
            .put(JSONObject().put("tag","mcrf-dns-direct"))
            .put(JSONObject().put("tag","mcrf-dns-route-$id").put("detour",RouteServerPlan.tag(id))))
        .put("rules",JSONArray()
            .put(JSONObject().put("domain_suffix",JSONArray().put("main.test")).put("server","mcrf-dns-remote").put("action","route"))
            .put(JSONObject().put("domain_suffix",JSONArray().put("other.test")).put("server","mcrf-dns-route-$id").put("action","route"))
            .put(JSONObject().put("domain_suffix",JSONArray().put("direct.test")).put("server","mcrf-dns-direct").put("action","route"))
            .put(JSONObject().put("server","mcrf-dns-remote").put("action","route"))))
        .put("route",JSONObject().put("rules",JSONArray().put(JSONObject().put("action","resolve").put("strategy","prefer_ipv4"))))
        .put("inbounds",JSONArray().put(JSONObject().put("type","tun").put("address",JSONArray().put("2001:db8::2/126"))))
    @Test fun mainIpv4DoesNotNarrowOtherServerDirectCatchallOrTun() {
        val original=source();val before=original.toString()
        val config=RouteServerAddressFamily.apply(original,"primary",true,emptySet())
        val dns=config.getJSONObject("dns");val rules=dns.getJSONArray("rules")
        assertEquals("ipv4_only",rules.getJSONObject(0).getString("strategy"))
        for(index in 1..3)assertFalse(rules.getJSONObject(index).has("strategy"))
        assertEquals("prefer_ipv4",dns.getString("strategy"))
        assertEquals(original.getJSONArray("inbounds").toString(),config.getJSONArray("inbounds").toString())
        assertEquals(original.getJSONObject("route").toString(),config.getJSONObject("route").toString())
        assertEquals(before,original.toString())
    }
    @Test fun additionalIpv4OnlyNarrowsItsOwnScopedDnsRule() {
        val config=RouteServerAddressFamily.apply(source(),"primary",false,setOf(id))
        val rules=config.getJSONObject("dns").getJSONArray("rules")
        assertEquals("ipv4_only",rules.getJSONObject(1).getString("strategy"))
        for(index in listOf(0,2,3))assertFalse(rules.getJSONObject(index).has("strategy"))
    }
    @Test fun explicitIpv6ConflictFailsWithoutEditingOtherPathsAndGlobalIpv4StaysGlobal() {
        val original=source();original.getJSONObject("dns").getJSONArray("rules").getJSONObject(1).put("strategy","ipv6_only")
        val before=original.toString()
        assertThrows(IllegalArgumentException::class.java){RouteServerAddressFamily.apply(original,"primary",false,setOf(id))}
        assertEquals(before,original.toString())
        val global=CarrierAddressFamily.ipv4Only(source())
        val mixed=RouteServerAddressFamily.apply(global,"primary",false,setOf(id))
        assertEquals("ipv4_only",mixed.getJSONObject("dns").getString("strategy"))
    }
}
