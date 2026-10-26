package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompactBootstrapContextTest {
    private fun state()=RouteRules.defaults().put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",true)
        .put("local",JSONObject().put("provider","Cloudflare").put("protocol","DoT")))
    @Test fun explicitBasicTagAndFamilyRemainBoundWithoutSourceMutation(){
        val input=state();val out=JSONObject().put("domain_resolver",JSONObject().put("server","bootstrap").put("strategy","ipv6_only"))
        val before=input.toString();val original=out.toString();val context=ServerBootstrapPolicy.capture(input).optionalContext(out)
        assertEquals("tls",context.getJSONObject("endpoint").getString("type"));assertEquals("1.1.1.1",context.getJSONObject("endpoint").getString("server"))
        assertEquals(853,context.getJSONObject("endpoint").getInt("server_port"));assertEquals("ipv6_only",context.getString("strategy"))
        assertEquals(before,input.toString());assertEquals(original,out.toString())
    }
    @Test fun unknownResolverOrUnrepresentableBindingDoesNotBecomeGlobalDns(){
        val policy=ServerBootstrapPolicy.capture(state())
        assertThrows(IllegalArgumentException::class.java){policy.optionalContext(JSONObject().put("domain_resolver","unknown-profile-table"))}
        assertThrows(IllegalArgumentException::class.java){policy.optionalContext(JSONObject().put("domain_resolver",JSONObject().put("server","bootstrap").put("disable_cache",true)))}
    }
    @Test fun customResolverUsesItsOriginalTagAndLiteralEndpoint(){
        val input=state();val custom=JSONObject().put("servers",JSONArray().put(JSONObject().put("type","udp").put("tag","owned-custom").put("server","192.0.2.53").put("server_port",5353))).put("final","owned-custom")
        input.getJSONObject("advancedDns").put("custom",custom.toString())
        val context=ServerBootstrapPolicy.capture(input).optionalContext(JSONObject().put("domain_resolver","owned-custom"))
        assertEquals("192.0.2.53",context.getJSONObject("endpoint").getString("server"));assertEquals(5353,context.getJSONObject("endpoint").getInt("server_port"))
        assertThrows(IllegalArgumentException::class.java){ServerBootstrapPolicy.capture(input).optionalContext(JSONObject().put("domain_resolver","bootstrap"))}
    }
}
