package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DnsRetirementTest {
    private fun legacy()=DnsConfig.defaults().put("provider","Google").put("tamperProtection",JSONObject()
        .put("enabled",true).put("selected","Quad9"))
    @Test fun malformedRetiredPolicyCannotBlockConfiguredResolver() {
        for(retired in listOf<Any>(true,"damaged",JSONObject(),JSONArray())) {
            val dns=legacy().put("tamperProtection",retired)
            assertEquals("8.8.8.8",DnsConfig.build(dns,"chosen","bootstrap").getString("server"))
            assertTrue(dns.has("tamperProtection")) // Generating a config never mutates saved data.
        }
    }
    @Test fun migrationPreservesResolverRulesAndOtherMetadataAndIsIdempotent() {
        val resolver=legacy().put("metadata",JSONObject().put("retain",17))
        val rule=JSONObject().put("id","keep").put("text","geosite:ru").put("dns",legacy())
        val root=JSONObject().put("globalDns",resolver).put("rules",JSONArray().put(rule))
            .put("advancedDns",AdvancedDnsConfig.defaults().put("direct",legacy()))
            .put("experienceRouting",JSONObject().put("expert",JSONObject().put("rules",JSONArray().put(JSONObject(rule.toString())))))
        assertTrue(DnsConfig.retireLegacyPolicies(root))
        assertFalse(root.toString().contains("tamperProtection"))
        assertEquals("Google",resolver.getString("provider"));assertEquals(17,resolver.getJSONObject("metadata").getInt("retain"))
        assertEquals("keep",root.getJSONArray("rules").getJSONObject(0).getString("id"))
        assertEquals("geosite:ru",rule.getString("text"))
        assertFalse(DnsConfig.retireLegacyPolicies(root))
    }
    @Test fun editorNoOpRetiresOnlyRemovedPolicy() {
        val original=legacy().put("metadata","retain")
        val visible=DnsConfig.defaults().put("provider","Google")
        val saved=AdvancedDnsEditorChanges.value(original,visible)
        assertEquals("retain",saved.getString("metadata"));assertFalse(saved.has("tamperProtection"))
        assertTrue(original.has("tamperProtection"))
        assertFalse(AdvancedDnsEditorChanges.value(original,visible,true).has("tamperProtection"))
    }
    @Test fun migrationDoesNotWalkUnrelatedImportedMetadata() {
        val root=JSONObject().put("globalDns",legacy()).put("metadata",legacy())
            .put("routingGroups",JSONObject().put("groups",JSONArray().put(JSONObject().put("data",JSONObject().put("rules",JSONArray().put(JSONObject().put("dns",legacy())))))))
            .put("temporaryRules",JSONObject().put("sessions",JSONArray().put(JSONObject().put("rule",JSONObject().put("dns",legacy())))))
        assertTrue(DnsConfig.retireLegacyPolicies(root))
        assertTrue(root.getJSONObject("metadata").has("tamperProtection"))
        assertFalse(root.getJSONObject("routingGroups").toString().contains("tamperProtection"))
        assertFalse(root.getJSONObject("temporaryRules").toString().contains("tamperProtection"))
    }
    @Test fun generatedAndAdvancedDnsDoNotInstallWitnessesOrSelectionRules() {
        val config=JSONObject("""{"outbounds":[{"tag":"direct","type":"direct"},{"tag":"proxy","type":"socks","server":"127.0.0.1","server_port":1234}],"route":{"rules":[],"final":"proxy"}}""")
        DnsConfig.applyGenerated(config,legacy())
        assertEquals(2,config.getJSONObject("dns").getJSONArray("servers").length())
        assertFalse(config.getJSONObject("dns").has("rules"))
        val state=RouteRules.defaults().put("globalDns",legacy()).put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",true))
        AdvancedDnsConfig.apply(config,state,"proxy","direct",true)
        assertFalse(config.toString().contains("tamper"))
        assertEquals("8.8.8.8",config.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("server"))
    }
}
