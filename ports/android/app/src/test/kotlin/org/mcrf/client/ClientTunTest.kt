package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClientTunTest {
    private fun auth(enabled: Boolean = true) = JSONObject().put("enabled",enabled).put("username","test-user").put("password","unit-test-password")
    private fun config(rules: JSONArray = JSONArray()) = JSONObject().put("outbounds",JSONArray().put(JSONObject().put("type","direct").put("tag","direct")).put(JSONObject().put("type","vless").put("tag","proxy")))
        .put("route",JSONObject().put("rules",rules).put("final","proxy"))
        .put("inbounds",JSONArray().put(JSONObject().put("type","tun").put("tag","tun-in")).put(JSONObject().put("type","mixed").put("tag","local-proxy").put("listen","127.0.0.1").put("listen_port",10808)))
    private fun rule(cidrs: String, tag: String) = JSONObject().put("ip_cidr",JSONArray().put(cidrs)).put("action","route").put("outbound",tag)
    private fun reject(run: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { run() } }
    @Test fun defaultsRoundTrip() { val s = ClientTunSettings(); assertEquals(s,ClientTunSettings.fromJson(s.json())); assertFalse(s.shareLan); assertFalse(s.httpProxy) }
    @Test fun mtuHonorsIpv6Minimum() { reject { ClientTunSettings(mtu=1279).validate() }; ClientTunSettings(mtu=600,ipv6=false).validate() }
    @Test fun rejectsHostnameCidrAndBadAddress() { reject { ClientTunCidr.parse("example.org/24") }; reject { ClientTunCidr.parse("999.1.2.3/24") }; reject { ClientTunCidr.parse("::1/129") } }
    @Test fun subtractionKeepsExplicitIpv4Route() { val result = ClientTunCidr.excluding(listOf(ClientTunCidr.parse("10.0.0.0/8")),listOf(ClientTunCidr.parse("10.2.0.0/16"))); assertFalse(result.any { it.overlaps(ClientTunCidr.parse("10.2.0.0/16")) }); assertTrue(result.any { it.overlaps(ClientTunCidr.parse("10.3.1.1/32")) }) }
    @Test fun subtractionKeepsExplicitIpv6Route() { val result = ClientTunCidr.parse("fc00::/7").subtract(ClientTunCidr.parse("fd00::/8")); assertEquals(listOf("fc00:0:0:0:0:0:0:0/8"),result.map { it.toString() }) }
    @Test fun overlappingWholeRangeRemovesIt() { assertTrue(ClientTunCidr.parse("10.1.0.0/16").subtract(ClientTunCidr.parse("10.0.0.0/8")).isEmpty()) }
    @Test fun excludesOnlyUnconditionalDirectIps() { val rules = JSONArray().put(rule("10.2.0.0/16","proxy")).put(rule("10.0.0.0/8","direct")); val result = ClientTunConfig.directExclusions(config(rules),ClientTunSettings()); assertTrue(result.isNotEmpty()); assertFalse(result.map { ClientTunCidr.parse(it) }.any { it.overlaps(ClientTunCidr.parse("10.2.0.0/16")) }) }
    @Test fun earlierDomainPreventsBypass() { val rules = JSONArray().put(JSONObject().put("domain",JSONArray().put("example.org")).put("outbound","proxy")).put(rule("10.0.0.0/8","direct")); assertTrue(ClientTunConfig.directExclusions(config(rules),ClientTunSettings()).isEmpty()) }
    @Test fun privateFallbackPreservesEarlierRules() { val c = config(JSONArray().put(rule("10.1.0.0/16","proxy"))); val result = ClientTunConfig.apply(c,ClientTunSettings(bypassPrivate=true),auth(),10808); assertEquals("proxy",result.config.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("outbound")); assertEquals(2,result.config.getJSONObject("route").getJSONArray("rules").length()); assertEquals(1,c.getJSONObject("route").getJSONArray("rules").length()) }
    @Test fun ipv6OffRemovesTunAddress() { val result = ClientTunConfig.apply(config(),ClientTunSettings(ipv6=false),auth(),10808); assertEquals(1,result.config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("address").length()) }
    @Test fun mixedOffStillHasTun() { val result = ClientTunConfig.apply(config(),ClientTunSettings(mixedEnabled=false),auth(),10808); assertNull(result.localPort); assertEquals(1,result.config.getJSONArray("inbounds").length()) }
    @Test fun lanRequiresAuth() { reject { ClientTunSettings(shareLan=true).validate(auth(false)) } }
    @Test fun lanUsesSeparatePortAndCredentials() { val result = ClientTunConfig.apply(config(),ClientTunSettings(shareLan=true),auth(),10808); val lan = result.config.getJSONArray("inbounds").getJSONObject(2); assertEquals("0.0.0.0",lan.getString("listen")); assertEquals(10809,lan.getInt("listen_port")); assertEquals("test-user",lan.getJSONArray("users").getJSONObject(0).getString("username")) }
    @Test fun authenticatedHttpHandoffRejected() { reject { ClientTunSettings(httpProxy=true).validate(auth()) } }
    @Test fun unauthenticatedHttpHandoffMapsNativePlatform() { val result = ClientTunConfig.apply(config(),ClientTunSettings(httpProxy=true,httpBypass="*.example.org"),auth(false),12345); val p = result.config.getJSONArray("inbounds").getJSONObject(0).getJSONObject("platform").getJSONObject("http_proxy"); assertEquals(12345,p.getInt("server_port")); assertEquals("*.example.org",p.getJSONArray("bypass_domain").getString(0)) }
    @Test fun rejectsListenerPortCollision() { reject { ClientTunConfig.apply(config(),ClientTunSettings(shareLan=true,lanPort=10808),auth(),10808) } }
    @Test fun dnsListenerRoutesToActualDnsResolver() { val result = ClientTunConfig.apply(config(),ClientTunSettings(dnsPort=1053),auth(),10808); assertEquals("hijack-dns",result.config.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("action")); assertEquals("direct",result.config.getJSONArray("inbounds").getJSONObject(2).getString("type")) }
    @Test fun customInboundsRejectLanAndReservedTags() { reject { ClientTunSettings(customInbounds="""[{"type":"mixed","tag":"custom","listen":"0.0.0.0","listen_port":9999}]""").validate() }; reject { ClientTunSettings(customInbounds="""[{"type":"mixed","tag":"tun-in","listen":"127.0.0.1","listen_port":9999}]""").validate() } }
    @Test fun randomPortIsUserPort() { assertTrue(ClientTunSettings(randomPort=true).choosePort() in 1024..65535) }
    @Test fun tunOwnSubnetNeverBypassed() { assertTrue(ClientTunConfig.directExclusions(config(JSONArray().put(rule("172.19.0.0/30","direct"))),ClientTunSettings()).isEmpty()) }
    @Test fun rejectsLoopbackAndMulticastTunAddresses() { reject { ClientTunSettings(address4="127.0.0.1/30").validate() }; reject { ClientTunSettings(address6="ff00::1/126").validate() } }
    @Test fun rejectsDnsAndApiPortCollision() { val c = config().put("services",JSONArray().put(JSONObject().put("type","api").put("listen_port",10808))); reject { ClientTunConfig.apply(c,ClientTunSettings(),auth(),10808) }; reject { ClientTunConfig.apply(config(),ClientTunSettings(dnsPort=10808),auth(),10808) } }
    @Test fun rejectsWrongTypesAndUnknownBackupSettings() { reject { ClientTunSettings.fromJson(JSONObject().put("mtu","bad")) }; reject { ClientTunSettings.fromJson(JSONObject().put("futureSetting",true)) } }
    @Test fun rejectsUnknownCustomNativeFieldBeforePersisting() { reject { ClientTunSettings(customInbounds="""[{"type":"mixed","tag":"custom","listen":"127.0.0.1","listen_port":9999,"made_up":true}]""").validate() } }
    @Test fun emptyPrivateBypassCannotBecomeCatchAllDirect() { reject { ClientTunSettings(bypassPrivate=true,privateRanges="\n# no networks").validate() }; ClientTunSettings(bypassPrivate=false,privateRanges="").validate() }
    @Test fun portCollisionsRejectedBeforePersisting() { reject { ClientTunSettings(shareLan=true,lanPort=10808).validate() }; reject { ClientTunSettings(dnsPort=10808).validate() } }
    @Test fun wrongCustomUsersAndPortTypesRejectedBeforePersisting() { reject { ClientTunSettings(customInbounds="""[{"type":"mixed","tag":"custom","listen":"127.0.0.1","listen_port":"9999"}]""").validate() }; reject { ClientTunSettings(customInbounds="""[{"type":"mixed","tag":"custom","listen":"127.0.0.1","listen_port":9999,"users":"bad"}]""").validate() } }
}
