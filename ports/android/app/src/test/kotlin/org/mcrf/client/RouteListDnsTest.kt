package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteListDnsTest {
    private val domainId="0123456789abcdef"
    private val ipId="fedcba9876543210"
    private fun source()=JSONObject("""{"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1234}],"route":{"final":"proxy","rules":[]}}""")
    private fun state(text:String,ids:List<String>,invert:Boolean=false)=RouteRules.defaults().apply {
        getJSONArray("rules").put(JSONObject().put("id","owned").put("name","Owned").put("mode","addresses")
            .put("text",text).put("target","proxy").put("invert",invert).put("enabled",true)
            .put("dns",DnsConfig.defaults()).apply {if(ids.isNotEmpty())put("presetIds",JSONArray(ids))})
    }
    private fun compile(state:JSONObject)=RouteRules.apply(source(),state,true,
        mapOf(domainId to "/fixture/domain.srs",ipId to "/fixture/ip.srs"),setOf(PresetRules.tag(ipId)))
    @Test fun ipOnlyPresetDoesNotGenerateDnsMatchOrUnusedResolver() {
        val state=state("",listOf(ipId));val before=state.toString()
        val result=compile(state)
        assertFalse(result.has("dns"))
        assertTrue(result.getJSONObject("route").getJSONArray("rules").toString().contains(PresetRules.tag(ipId)))
        assertEquals(before,state.toString())
    }
    @Test fun positiveDomainAndDomainPresetKeepBothOrBranchesWithoutIpPreset() {
        val result=compile(state("first.fixture.test",listOf(domainId,ipId)))
        val match=result.getJSONObject("dns").getJSONArray("rules").getJSONObject(0)
        assertEquals("or",match.getString("mode"))
        val parts=match.getJSONArray("rules")
        assertEquals(2,parts.length())
        assertEquals("first.fixture.test",parts.getJSONObject(0).getJSONArray("domain_suffix").getString(0))
        assertEquals(PresetRules.tag(domainId),parts.getJSONObject(1).getJSONArray("rule_set").getString(0))
        assertFalse(match.toString().contains(PresetRules.tag(ipId)))
        assertEquals("proxy",result.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("detour"))
    }
    @Test fun invertedIpPresetOrLiteralIpCannotGenerateDomainComplement() {
        for(rule in listOf(state("first.fixture.test",listOf(domainId,ipId),true),
            state("first.fixture.test\n192.0.2.0/24",listOf(domainId),true))) {
            val before=rule.toString();val result=compile(rule)
            assertFalse(result.has("dns"))
            assertTrue(result.getJSONObject("route").getJSONArray("rules").toString().contains("\"invert\":true"))
            assertEquals(before,rule.toString())
        }
    }
    @Test fun invertedDomainsRetainOuterComplementAcrossBothOrBranches() {
        val match=compile(state("first.fixture.test",listOf(domainId),true))
            .getJSONObject("dns").getJSONArray("rules").getJSONObject(0)
        assertTrue(match.getBoolean("invert"));assertEquals("or",match.getString("mode"))
        assertEquals(2,match.getJSONArray("rules").length())
    }
    @Test fun bothAddressFamilyCatchallStillAllowsExplicitDns() {
        val match=compile(state("0.0.0.0/0\n::/0",emptyList()))
            .getJSONObject("dns").getJSONArray("rules").getJSONObject(0)
        assertEquals(setOf("action","server"),match.keys().asSequence().toSet())
    }
}
