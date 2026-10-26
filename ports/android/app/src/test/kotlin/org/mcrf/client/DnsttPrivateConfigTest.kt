package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DnsttPrivateConfigTest {
    private fun profile()=JSONObject().put("type","dnstt").put("domain","t.example.test").put("key","ab".repeat(32))
    @Test fun nativeSchemaStripsDisplayProbeAndDispatcherMetadata(){
        val config=DnsttPrivateConfig.create(profile().put("name","Private name").put("probe_only",true)
            .put("outbound_tag","route-secret").put("device_id","untrusted"),12345,"local","private")
        assertEquals(setOf("domain","key","doh","socks","socks_user","socks_pass"),config.keys().asSequence().toSet())
        assertEquals("https://cloudflare-dns.com/dns-query",config.getString("doh"))
        assertEquals("127.0.0.1:12345",config.getString("socks"))
    }
    @Test fun nativeSchemaPreservesExplicitResolverAndRemoteCredentials(){
        val config=DnsttPrivateConfig.create(profile().put("udp","[::1]:5353").put("username","remote + user")
            .put("password","private + secret"),12345,"local","private")
        assertEquals("[::1]:5353",config.getString("udp"));assertFalse(config.has("doh"))
        assertEquals("remote + user",config.getString("username"));assertEquals("private + secret",config.getString("password"))
    }
}
