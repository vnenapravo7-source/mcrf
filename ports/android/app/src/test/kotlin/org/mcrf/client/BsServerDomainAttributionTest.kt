package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BsServerDomainAttributionTest {
    @Test fun serverModesRetainDnsPolicyAndEnableDomainAttribution() {
        for(mode in listOf("server","server_first","mcrf_first")) {
            val source=JSONObject("""{"dns":{"servers":[{"type":"local","tag":"local"}],"final":"local","disable_cache":true,"rules":[]}}""")
            val before=JSONObject(source.getJSONObject("dns").toString())
            BsNativeProfilePreparation.enableServerDomainAttribution(source,mode)
            val dns=source.getJSONObject("dns")
            assertTrue(dns.getBoolean("reverse_mapping"))
            dns.remove("reverse_mapping")
            assertEquals(before.toString(),dns.toString())
        }
    }
    @Test fun localOnlyPreservesExplicitAndDefaultDnsPreferences() {
        for(raw in listOf("{}","{\"dns\":{}}","{\"dns\":{\"reverse_mapping\":false}}","{\"dns\":{\"reverse_mapping\":true}}")) {
            val source=JSONObject(raw);val before=source.toString()
            BsNativeProfilePreparation.enableServerDomainAttribution(source,"mcrf")
            assertEquals(before,source.toString())
        }
    }
}
