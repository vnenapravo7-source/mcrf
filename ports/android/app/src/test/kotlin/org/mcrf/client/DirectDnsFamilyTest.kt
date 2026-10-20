package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Same P0 network batch. The flag is a captured physical-network property, not a user pref. */
class DirectDnsFamilyTest {
    private fun base()=JSONObject("""{"outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":12345}],"route":{"final":"proxy","default_domain_resolver":"chosen","rules":[]},"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"]}]}""")
    private fun state()=RouteRules.defaults().put("defaultTarget","direct").apply {
        getJSONArray("rules").put(RouteRules.outsideRussia("ru\nyandex.ru\n77.88.0.0/18\n2a02:6b8::/32"))
    }
    private fun generated()=base().also {DnsConfig.applyGenerated(it,DnsConfig.defaults())}
    private fun rules(dns:JSONObject)=(0 until dns.getJSONArray("rules").length()).map {dns.getJSONArray("rules").getJSONObject(it)}
    @Test fun familyRestartIsOnlyForAnActualAppliedPolicyChange() {
        assertFalse(DirectDnsFamily.requiresRestart(null,true));assertFalse(DirectDnsFamily.requiresRestart(null,false))
        assertFalse(DirectDnsFamily.requiresRestart(true,true));assertFalse(DirectDnsFamily.requiresRestart(false,false))
        assertTrue(DirectDnsFamily.requiresRestart(true,false));assertTrue(DirectDnsFamily.requiresRestart(false,true))
    }
    @Test fun generatedPolicyUsesVisibleSystemDirectAndVpnRemoteEvenForLegacyDisabledSnapshot() {
        val settings=state();val before=settings.toString()
        val c=RouteRules.apply(generated(),settings,true)
        val dns=c.getJSONObject("dns");val servers=dns.getJSONArray("servers")
        val direct=(0 until servers.length()).map {servers.getJSONObject(it)}.single {it.optString("tag")=="mcrf-dns-direct"}
        val remote=(0 until servers.length()).map {servers.getJSONObject(it)}.single {it.optString("tag")=="mcrf-dns-remote"}
        assertEquals("local",direct.getString("type"));assertEquals("proxy",remote.getString("detour"))
        assertEquals("mcrf-dns-remote",dns.getString("final"))
        assertTrue(rules(dns).any {it.optString("server")=="mcrf-dns-direct"&&it.has("domain_suffix")})
        assertEquals(before,settings.toString())
    }
    @Test fun noPhysicalIpv6KeepsDnsAnswersForVpnAndTunAddress() {
        val c=RouteRules.apply(generated(),state(),true,directIpv4Only=true)
        val dns=c.getJSONObject("dns");val noAAAA=rules(dns).filter {it.optString("action")=="predefined"}
        assertTrue(noAAAA.isEmpty())
        assertEquals("mcrf-dns-remote",rules(dns).last().getString("server"))
        assertEquals("fdfe:dcba:9876::1/126",c.getJSONArray("inbounds").getJSONObject(0).getJSONArray("address").getString(1))
    }
    @Test fun standardCountryDirectCachedIpv6FailsBeforeSyntheticTcpAccept() {
        val route=RouteRules.apply(generated(),state(),true,directIpv4Only=true).getJSONObject("route").getJSONArray("rules")
        val items=(0 until route.length()).map {route.getJSONObject(it)}
        val guard=items.single {it.optString("method")=="reply"}
        assertEquals("tcp",guard.getString("network"));assertEquals(6,guard.getInt("ip_version"))
        assertEquals("2a02:6b8::/32",guard.getJSONArray("ip_cidr").getString(0))
        assertTrue(items.indexOf(guard)<items.indexOfFirst {it.optString("action")=="sniff"})
        assertTrue(items.indexOf(guard)<items.indexOfFirst {it.optString("action")=="resolve"})
    }
    @Test fun cachedIpv6GuardNeverOverridesEarlierVpnAppOrDomainPoliciesOrLocalIpv6() {
        val forced=state().put("vpnApps",JSONArray().put("org.example.forced"))
        assertNull(DirectDnsFamily.earlyReject(RouteRules.apply(generated(),forced,true),forced))
        val extra=state().apply {val old=getJSONArray("rules");put("rules",JSONArray().put(JSONObject().put("id","force").put("name","Force").put("text","example.ru").put("mode","addresses").put("target","proxy")).put(old.getJSONObject(0)))}
        assertNull(DirectDnsFamily.earlyReject(RouteRules.apply(generated(),extra,true),extra))
        val local=state().apply {getJSONArray("rules").getJSONObject(0).put("text","ru\nfd00::/8\nfe80::/10\n::/0")}
        assertNull(DirectDnsFamily.earlyReject(RouteRules.apply(generated(),local,true),local))
    }
    @Test fun applicationExclusionsRemainNativeTunExclusionsNotRewrittenToVpn() {
        val settings=state().put("excludedApps",JSONArray().put("org.example.internetometer"))
        val c=RouteRules.apply(generated(),settings,true,directIpv4Only=true)
        assertEquals("org.example.internetometer",c.getJSONArray("inbounds").getJSONObject(0).getJSONArray("exclude_package").getString(0))
    }
    @Test fun firstDirectAppRuleGetsEarlyFailureWithoutBlockingOtherAppsVpnIpv6() {
        val settings=RouteRules.defaults().apply {getJSONArray("rules").put(JSONObject().put("id","app").put("name","Direct app").put("mode","apps").put("text","app:org.example.internetometer").put("target","direct"))}
        val route=RouteRules.apply(generated(),settings,true,directIpv4Only=true).getJSONObject("route").getJSONArray("rules")
        val items=(0 until route.length()).map {route.getJSONObject(it)}
        val guard=items.single {it.optString("method")=="reply"}
        assertEquals("org.example.internetometer",guard.getJSONArray("package_name").getString(0))
        assertEquals("2000::/3",guard.getJSONArray("ip_cidr").getString(0))
        assertTrue(items.indexOf(guard)<items.indexOfFirst {it.optString("action")=="sniff"})
    }
    @Test fun directAppRuleUsesDirectDnsWithoutOsBypassOrSuppressingOtherAppsIpv6() {
        val settings=RouteRules.defaults().apply {getJSONArray("rules").put(JSONObject().put("id","app").put("name","Direct app").put("mode","apps").put("text","app:org.example.internetometer").put("target","direct"))}
        for(no6 in listOf(false,true)) {
            val c=RouteRules.apply(generated(),settings,true,directIpv4Only=no6)
            val dns=rules(c.getJSONObject("dns"))
            assertEquals("mcrf-dns-direct",dns.single {it.has("package_name")}.getString("server"))
            assertFalse(dns.any {it.optString("action")=="predefined"})
            assertEquals("mcrf-dns-remote",dns.last().getString("server"))
            assertFalse(c.getJSONArray("inbounds").getJSONObject(0).has("exclude_package"))
        }
    }
    @Test fun forcedVpnAppRetainsAAAAOnIpv4PhysicalNetworkAndAppPriority() {
        val settings=state().put("vpnApps",JSONArray().put("org.example.forced"))
        val c=RouteRules.apply(generated(),settings,true,directIpv4Only=true)
        val dns=rules(c.getJSONObject("dns"))
        assertEquals("mcrf-dns-remote",dns.first {it.has("package_name")}.getString("server"))
        assertFalse(dns.any {it.optString("action")=="predefined"})
        assertFalse((0 until c.getJSONObject("route").getJSONArray("rules").length()).any {c.getJSONObject("route").getJSONArray("rules").getJSONObject(it).optString("method")=="reply"})
    }
    @Test fun conditionalAppWithIpDoesNotBecomeUnconditionalAppDns() {
        val settings=RouteRules.defaults().apply {getJSONArray("rules").put(JSONObject().put("id","app").put("name","Conditional").put("mode","in-apps").put("text","app:org.example.internetometer\n192.0.2.0/24").put("target","direct"))}
        val c=RouteRules.apply(generated(),settings,true,directIpv4Only=true)
        assertFalse(rules(c.getJSONObject("dns")).any {it.has("package_name")})
        assertNull(DirectDnsFamily.earlyReject(c,settings))
    }
    @Test fun knownPhysicalIpv6AndUnknownNetworkDoNotAddAutomaticAAAAFilter() {
        assertFalse(rules(RouteRules.apply(generated(),state(),true,directIpv4Only=false).getJSONObject("dns")).any {it.optString("action")=="predefined"})
    }
    @Test fun importedJsonDnsAndDisabledHiddenExtrasAreNotSilentlyActivated() {
        val imported=base().put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("type","udp").put("tag","own").put("server","192.0.2.53"))).put("final","own"))
        val original=imported.getJSONObject("dns").toString()
        assertEquals(original,RouteRules.apply(imported,state(),true,directIpv4Only=true).getJSONObject("dns").toString())
        val saved=state().put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",false).put("fakeIp",true).put("hosts",true).put("custom","not-active-json"))
        val effective=DirectDnsFamily.settings(saved,true,true)
        assertTrue(effective.getBoolean("enabled"));assertFalse(effective.getBoolean("fakeIp"));assertFalse(effective.getBoolean("hosts"));assertEquals("",effective.getString("custom"))
        assertFalse(saved.getJSONObject("advancedDns").getBoolean("enabled"))
    }
    @Test fun explicitProfileDnsSurvivesGeneratedPreparationIncludingResolverReference() {
        val c=base();CompatibilityImportDns.install(c,listOf("192.0.2.53"))
        val before=c.toString();DnsConfig.applyGenerated(c,DnsConfig.defaults())
        assertEquals(before,c.toString());assertFalse(DnsConfig.appOwned(c))
    }
    @Test fun basicPolicyDoesNotResolveBeforeEarlierDomainBlockOrAppPolicy() {
        val settings=state();val old=settings.getJSONArray("rules")
        settings.put("rules",JSONArray().put(JSONObject().put("id","blocked").put("name","Blocked").put("mode","addresses").put("text","blocked.example").put("target","block")).put(old.getJSONObject(0)))
        val route=RouteRules.apply(generated(),settings,true,directIpv4Only=true).getJSONObject("route").getJSONArray("rules")
        val items=(0 until route.length()).map {route.getJSONObject(it)}
        assertTrue(items.indexOfFirst {it.optString("action")=="reject"}<items.indexOfFirst {it.optString("action")=="resolve"})
    }
}
