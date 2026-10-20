package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CarrierAddressFamilyTest {
    private fun config() = JSONObject("""{
      "dns":{"servers":[{"type":"udp","tag":"remote","server":"192.0.2.53","detour":"proxy"}],"rules":[
        {"domain":["blocked.test"],"action":"predefined","rcode":"NXDOMAIN"},
        {"domain":["direct.test"],"action":"route","server":"remote","strategy":"prefer_ipv4"}],"final":"remote"},
      "route":{"final":"proxy","rules":[{"ip_cidr":["127.0.0.0/8","::1/128"],"action":"route","outbound":"loopback"},
        {"action":"resolve","strategy":"prefer_ipv4"},{"ip_cidr":["192.0.2.0/24"],"action":"route","outbound":"direct"}]},
      "inbounds":[{"type":"tun","address":["172.19.0.1/30","fd00::1/126"],"strict_route":true}],
      "outbounds":[{"tag":"proxy","type":"socks","server":"127.0.0.1","server_port":12345,"username":"fixture","password":"fixture"},{"type":"direct","tag":"direct"}]
    }""")
    @Test fun knownIpv4BridgesUseNativeDnsIpv4OnlyWithoutChangingSavedInput() {
        for (kind in listOf("csqtt","csqtt25","qwdtt")) {
            val source=config();val before=source.toString();val result=CarrierAddressFamily.apply(source,kind)
            assertEquals(before,source.toString());assertNotSame(source,result)
            assertEquals("ipv4_only",result.getJSONObject("dns").getString("strategy"))
            assertEquals("ipv4_only",result.getJSONObject("dns").getJSONArray("rules").getJSONObject(1).getString("strategy"))
            assertEquals("ipv4_only",result.getJSONObject("route").getJSONArray("rules").getJSONObject(1).getString("strategy"))
        }
    }
    @Test fun unrelatedEnginesAndOrdinaryVpnAreUnchanged() {
        for(kind in listOf(null,"wdtt","openflux","singbox","vless","unknown")) {
            val source=config();assertSame(source,CarrierAddressFamily.apply(source,kind))
        }
    }
    @Test fun noRuleReorderingOrDirectProxyTlsOrTunChanges() {
        val source=config();val result=CarrierAddressFamily.apply(source,"csqtt")
        for(key in listOf("inbounds","outbounds"))assertEquals(source.get(key).toString(),result.get(key).toString())
        val old=source.getJSONObject("route").getJSONArray("rules");val rules=result.getJSONObject("route").getJSONArray("rules")
        assertEquals(old.length(),rules.length());for(i in listOf(0,2))assertEquals(old.get(i).toString(),rules.get(i).toString())
        assertEquals("NXDOMAIN",result.getJSONObject("dns").getJSONArray("rules").getJSONObject(0).getString("rcode"))
        assertEquals(source.getJSONObject("dns").getJSONArray("servers").toString(),result.getJSONObject("dns").getJSONArray("servers").toString())
    }
    @Test fun explicitIpv6PolicyIsRejectedNotSilentlyOverwritten() {
        for(strategy in listOf("ipv6_only","prefer_ipv6")) {
            val source=config();source.getJSONObject("dns").put("strategy",strategy);val before=source.toString()
            try {CarrierAddressFamily.apply(source,"csqtt");fail("IPv6 intent must be reported")}catch(expected:IllegalArgumentException){assertTrue(expected.message!!.contains("IPv4"))}
            assertEquals(before,source.toString())
        }
    }
    @Test fun nestedRulesAndBootstrapResolverCannotReintroduceIpv6Preference() {
        val source=config();source.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
            .put("domain_resolver",JSONObject().put("server","bootstrap").put("strategy","prefer_ipv4"))
        source.getJSONObject("route").put("default_domain_resolver",JSONObject().put("server","remote").put("strategy","prefer_ipv4"))
        val result=CarrierAddressFamily.apply(source,"qwdtt")
        assertEquals("ipv4_only",result.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getJSONObject("domain_resolver").getString("strategy"))
        assertEquals("ipv4_only",result.getJSONObject("route").getJSONObject("default_domain_resolver").getString("strategy"))
    }
    @Test fun repeatedApplicationIsIdempotent() {
        val once=CarrierAddressFamily.apply(config(),"csqtt25")
        assertEquals(once.toString(),CarrierAddressFamily.apply(once,"csqtt25").toString())
    }
    @Test fun exportOwnedNativeDnsVector() {
        val source=JSONObject("""{"dns":{"servers":[{"type":"udp","tag":"owned-dns","server":"127.0.0.1","server_port":12345}],"final":"owned-dns","rules":[{"domain":["blocked.fixture.test"],"action":"predefined","rcode":"NXDOMAIN"}]},"inbounds":[{"type":"direct","tag":"owned-input","listen":"127.0.0.1","listen_port":12346,"network":"udp"}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rules":[{"inbound":["owned-input"],"action":"hijack-dns"}],"final":"direct"}}""")
        val fixed=CarrierAddressFamily.apply(source,"csqtt")
        assertEquals("ipv4_only",fixed.getJSONObject("dns").getString("strategy"))
        println("MCRF_CARRIER_FAMILY_VECTOR:"+java.util.Base64.getEncoder().encodeToString(JSONObject().put("original",source).put("fixed",fixed).toString().toByteArray(Charsets.UTF_8)))
    }
}
