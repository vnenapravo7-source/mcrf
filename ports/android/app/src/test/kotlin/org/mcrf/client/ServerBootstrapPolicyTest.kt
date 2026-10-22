package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure endpoint/query model; actual protected transport/cancel is not proved. */
class ServerBootstrapPolicyTest {
    private fun state(provider:String="Системный",protocol:String="Системный")=RouteRules.defaults().put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",true)
        .put("local",JSONObject().put("provider",provider).put("protocol",protocol)))
    @Test fun literalNeverQueriesAndDoesNotInheritWebsiteDns(){
        val policy=ServerBootstrapPolicy.capture(state("Cloudflare","UDP"))
        assertEquals("192.0.2.1",policy.ipv4("192.0.2.1"){_,_->throw AssertionError("literal must not query")})
        assertEquals("127.0.0.1",policy.ipv4("localhost"){_,_->throw AssertionError("reserved loopback must not query")})
        assertEquals("1.1.1.1",policy.endpoint.server)
    }
    @Test fun visibleServerDnsSurvivesTheRemovedLegacyOptIn(){
        val input=state("Cloudflare","DoT");input.getJSONObject("advancedDns").put("enabled",false).put("custom","legacy hidden JSON")
        val before=input.toString();val policy=ServerBootstrapPolicy.capture(input)
        assertEquals("tls",policy.endpoint.type);assertEquals("1.1.1.1",policy.endpoint.server)
        assertEquals(before,input.toString())
    }
    @Test fun customEncryptedBootstrapIsNotDowngradedToSystem(){
        val input=state("Свой","DoT")
        input.getJSONObject("advancedDns").getJSONObject("local").put("custom","tls://192.0.2.53:8853")
        val policy=ServerBootstrapPolicy.capture(input)
        assertEquals("tls",policy.endpoint.type);assertEquals(8853,policy.endpoint.port)
        var calls=0
        assertThrows(IllegalStateException::class.java){policy.ipv4("server.example.test"){endpoint,query->
            calls++;assertEquals("tls",endpoint.type);assertEquals(1,PacketDnsWire.question(query).type);throw IllegalStateException("owned selected resolver failure")
        }}
        assertEquals(1,calls)
    }
    @Test fun nativeBootstrapPreservesChosenProtocol(){
        listOf("AdGuard" to "DoQ","Cloudflare" to "DoH3").forEach {(provider,protocol)->
            val policy=ServerBootstrapPolicy.capture(state(provider,protocol))
            assertTrue(policy.requiresCore)
            val config=policy.coreConfig(12345,"u","p")
            assertEquals(if(protocol=="DoQ")"quic" else "h3",config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("type"))
            assertEquals(1,config.getJSONObject("dns").getJSONArray("servers").length())
            assertEquals("hijack-dns",config.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("action"))
        }
        val input=state();input.getJSONObject("advancedDns").put("custom","{}")
        assertThrows(IllegalArgumentException::class.java){ServerBootstrapPolicy.capture(input)}
    }
    @Test fun customDnsIsCopiedWithoutInventingSystemOrDroppingRules(){
        val input=state();val dns=JSONObject().put("servers",org.json.JSONArray().put(JSONObject().put("type","local").put("tag","chosen")))
            .put("final","chosen").put("rules",org.json.JSONArray().put(JSONObject().put("domain_suffix",org.json.JSONArray().put("test")).put("action","route").put("server","chosen")))
        input.getJSONObject("advancedDns").put("custom",dns.toString())
        val before=input.toString();val policy=ServerBootstrapPolicy.capture(input)
        assertEquals(dns.toString(),policy.coreConfig(12345,"u","p").getJSONObject("dns").toString())
        val mutated=policy.coreConfig(12345,"u","p");mutated.getJSONObject("dns").put("final","mutated")
        assertEquals("chosen",policy.coreConfig(12345,"u","p").getJSONObject("dns").getString("final"))
        assertEquals(before,input.toString())
    }
    @Test fun externalIpv6LimitationNeverQueriesOrCreatesGlobalNoAAAA(){
        val input=state();val before=input.toString();val policy=ServerBootstrapPolicy.capture(input)
        assertThrows(IllegalArgumentException::class.java){policy.ipv4("2001:db8::1"){_,_->throw AssertionError("literal must not query")}}
        assertEquals(before,input.toString())
    }
}
