package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class AdvancedDnsRoutingTest {
    @Test fun revertingVisibleFieldsAfterExplicitPolicyEditDoesNotReactivateOpaquePolicy(){val original=DnsConfig.defaults().put("providerMetadata",JSONObject().put("retain",true));assertTrue(AdvancedDnsEditorChanges.value(original,DnsConfig.defaults()).has("providerMetadata"));assertFalse(AdvancedDnsEditorChanges.value(original,DnsConfig.defaults(),true).has("providerMetadata"));assertTrue(original.has("providerMetadata"))}
    @Test fun firstPostedSelectionsAndEmptyCustomNeverEmitVisibleEdit(){val initial=JSONObject().put("protocol","DoH").put("provider","Cloudflare").put("providerMetadata",JSONObject().put("retain",true));val draft=AdvancedDnsEditorChanges.VisibleDraft(initial);assertFalse(draft.take(DnsConfig.defaults()));assertFalse(draft.take(DnsConfig.defaults()));assertTrue(draft.take(DnsConfig.defaults().put("protocol","DoT")));assertFalse(draft.take(DnsConfig.defaults().put("protocol","DoT")));assertTrue(draft.take(DnsConfig.defaults()))}
    @Test fun uneditedValuePreservesUnknownFieldsAndMissingCustomExactly(){val initial=JSONObject().put("protocol","DoH").put("provider","Cloudflare").put("providerMetadata",JSONObject().put("retain",true));val output=AdvancedDnsEditorChanges.value(initial,DnsConfig.defaults());assertTrue(RoutingDefaultsEdits.equivalent(initial,output));assertFalse(output.has("custom"));output.remove("providerMetadata");assertTrue(initial.has("providerMetadata"))}
    @Test fun androidDeepComparisonIgnoresKeyOrderButPreservesArrayOrder(){assertTrue(RoutingDefaultsEdits.equivalent(JSONObject("""{"a":1,"b":{"x":true}}"""),JSONObject("""{"b":{"x":true},"a":1.0}""")));assertFalse(RoutingDefaultsEdits.equivalent(JSONArray("[1,2]"),JSONArray("[2,1]")))}
    @Test fun resolverOpeningMissingCustomAndHiddenMetadataIsNotAnEdit(){val original=JSONObject().put("protocol","DoH").put("provider","Cloudflare").put("providerMetadata",JSONObject().put("retain",true));val visible=DnsConfig.defaults();assertNull(AdvancedDnsEditorChanges.resolver(original,visible));assertFalse(original.has("custom"));assertTrue(original.has("providerMetadata"))}
    @Test fun explicitResolverChangeUsesCanonicalPolicyAndRetainsOriginalUntilEdit(){val original=DnsConfig.defaults().put("providerMetadata",JSONObject().put("retain",true));val visible=DnsConfig.defaults().put("protocol","DoT");val changed=AdvancedDnsEditorChanges.resolver(original,visible)!!;assertEquals("DoT",changed.getString("protocol"));assertFalse(changed.has("providerMetadata"));assertTrue(original.getJSONObject("providerMetadata").getBoolean("retain"));assertEquals("DoH",original.getString("protocol"))}
    @Test fun revertingToOpeningValueUsesLastSuccessfullyCommittedBaseline(){val opened=AdvancedDnsConfig.defaults();val first=JSONObject(opened.toString()).put("timeout","5s");val reverted=RoutingDefaultsEdits.merge(first,first,opened);assertEquals("10s",reverted.getString("timeout"))}
    @Test fun unifiedGeoPresetSelectionRetainsSourcesAndLocalPolicies(){val original=state().put("geoData",GeoDataConfig.defaults().put("source","custom").put("geoipUrl","https://example.org/ip.dat"));original.getJSONArray("rules").put(rule("personal.example","direct"));val selected=JSONArray().put(JSONObject().put("kind","geoip").put("code","ru").put("target","direct")).put(JSONObject().put("kind","geosite").put("code","google").put("target","proxy"));val next=GeoDataPresets.selectors(original,selected);assertEquals("https://example.org/ip.dat",next.getJSONObject("geoData").getString("geoipUrl"));assertEquals(original.getJSONArray("rules").toString(),next.getJSONArray("rules").toString());assertFalse(original.getJSONObject("geoData").getJSONArray("selectors").length()>0);val config=RouteRules.apply(base(),next,true,mapOf("geo-0" to "/fixture/ru.json","geo-1" to "/fixture/google.json"));assertEquals(2,config.getJSONObject("route").getJSONArray("rule_set").length())}
    @Test(expected=IllegalArgumentException::class) fun unifiedGeoPickerCannotRewriteBoundProviderOrder(){val s=state().put("geoData",GeoDataConfig.fromProvider(JSONObject("""{"ProxySites":["geosite:google"]}""")));GeoDataPresets.selectors(s,JSONArray())}
    @Test(expected=IllegalArgumentException::class) fun unifiedGeoPickerRejectsDuplicateCategoryIdentity(){val r=JSONObject().put("kind","geoip").put("code","ru").put("target","direct");GeoDataPresets.selectors(state(),JSONArray().put(r).put(JSONObject(r.toString()).put("target","proxy")))}
    private fun base() = JSONObject("""{"outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1234}],"inbounds":[{"type":"tun","tag":"tun"}],"route":{"final":"proxy","rules":[]}}""")
    private fun state() = RouteRules.defaults().put("advancedDns", AdvancedDnsConfig.defaults().put("enabled", true))
    private fun rule(text: String, target: String = "direct") = JSONObject().put("id", "test").put("name", "Test").put("text", text).put("target", target).put("mode", "addresses")
    @Test fun resolverPathsAndIndependentAAAA() {
        val s = state(); s.getJSONObject("advancedDns").put("directNoAAAA", true); s.getJSONArray("rules").put(rule("example.com"))
        val dns = RouteRules.apply(base(), s, true).getJSONObject("dns")
        assertEquals("proxy", dns.getJSONArray("servers").getJSONObject(1).getString("detour"))
        assertFalse(dns.getJSONArray("servers").getJSONObject(2).has("detour"))
        val rules = dns.getJSONArray("rules"); assertEquals("predefined", rules.getJSONObject(0).getString("action")); assertEquals("AAAA", rules.getJSONObject(0).getJSONArray("query_type").getString(0)); assertEquals("mcrf-dns-direct", rules.getJSONObject(1).getString("server"))
    }
    @Test fun mixedAddressesDoNotBroadenDns() {
        val s = state(); s.getJSONArray("rules").put(rule("example.com\n192.0.2.0/24"))
        val match = RouteRules.apply(base(), s, true).getJSONObject("dns").getJSONArray("rules").getJSONObject(0)
        assertTrue(match.has("domain_suffix")); assertFalse(match.has("ip_cidr")); assertFalse(match.has("rules"))
    }
    @Test fun invertedMixedAddressesDoNotGuessDestinationIpDuringDns() {
        val s=state()
        s.getJSONObject("advancedDns").put("final","direct")
        s.getJSONArray("rules").put(rule("example.com\n192.0.2.0/24").put("target","proxy").put("invert",true))
        val before=s.toString()
        val c=RouteRules.apply(base(),s,true)
        val dnsRules=c.getJSONObject("dns").getJSONArray("rules")
        assertEquals(1,dnsRules.length())
        assertEquals("mcrf-dns-direct",dnsRules.getJSONObject(0).getString("server"))
        assertFalse(dnsRules.getJSONObject(0).has("invert"))
        val route=c.getJSONObject("route").getJSONArray("rules")
        val inverted=(0 until route.length()).map {route.getJSONObject(it)}.single {it.optBoolean("invert")}
        assertEquals("proxy",inverted.getString("outbound"))
        assertEquals(2,inverted.getJSONArray("rules").length())
        assertEquals(before,s.toString())
    }
    @Test fun invertedDomainOnlyStillSelectsDnsForItsComplement() {
        val s=state()
        s.getJSONObject("advancedDns").put("final","direct")
        s.getJSONArray("rules").put(rule("example.com").put("target","proxy").put("invert",true))
        val dns=RouteRules.apply(base(),s,true).getJSONObject("dns").getJSONArray("rules")
        assertTrue(dns.getJSONObject(0).getBoolean("invert"))
        assertEquals("mcrf-dns-remote",dns.getJSONObject(0).getString("server"))
    }
    @Test fun countryDomainsUseDirectDnsAndCountryRouteSurvives() {
        val s = state(); s.put("defaultTarget", "direct"); s.getJSONArray("rules").put(RouteRules.outsideRussia("ru\n192.0.2.0/24"))
        val c = RouteRules.apply(base(), s, true); assertEquals("direct", c.getJSONObject("route").getString("final")); val routes=c.getJSONObject("route").getJSONArray("rules");assertTrue((0 until routes.length()).any {routes.getJSONObject(it).optBoolean("invert")})
        val dns = c.getJSONObject("dns").getJSONArray("rules").getJSONObject(0); assertEquals("mcrf-dns-direct", dns.getString("server")); assertFalse(dns.has("invert"))
    }
    @Test fun ipOnlyCreatesNoCatchAllDnsRule() {
        val s = state(); s.getJSONArray("rules").put(rule("192.0.2.0/24")); assertEquals(1, RouteRules.apply(base(), s, true).getJSONObject("dns").getJSONArray("rules").length())
    }
    @Test fun fakeIpIpv6ToggleAndCache() {
        val s = state(); s.getJSONObject("advancedDns").put("fakeIp", true).put("fakeNoAAAA", false).put("optimistic", true)
        val dns = RouteRules.apply(base(), s, true).getJSONObject("dns"); assertEquals("2001:db8::/32", dns.getJSONArray("servers").getJSONObject(3).getString("inet6_range")); assertTrue(dns.getJSONObject("optimistic").getBoolean("enabled"))
    }
    @Test(expected = IllegalArgumentException::class) fun fakeIpRequiresVpn() { val s=state();s.getJSONObject("advancedDns").put("fakeIp",true);RouteRules.apply(base(),s,false) }
    @Test(expected = IllegalArgumentException::class) fun optimisticRequiresCache() { AdvancedDnsConfig.validate(AdvancedDnsConfig.defaults().put("cache",false).put("optimistic",true)) }
    @Test(expected = IllegalArgumentException::class) fun localResolverCycleRejected() { AdvancedDnsConfig.validate(AdvancedDnsConfig.defaults().put("local",JSONObject().put("provider","Свой").put("protocol","DoH").put("custom","https://resolver.example/dns-query"))) }
    @Test fun hostsFamiliesAndComments() { val a=AdvancedDnsConfig.answers("192.0.2.1 example.com alias.example # x\n2001:db8::1 example.com");assertEquals(2,a.getJSONArray("example.com").length());assertEquals(1,a.getJSONArray("alias.example").length()) }
    @Test(expected = IllegalArgumentException::class) fun malformedHostsRejected() { AdvancedDnsConfig.answers("bad example.com") }
    @Test(expected = IllegalArgumentException::class) fun invalidCustomReferenceRejected() { AdvancedDnsConfig.validate(AdvancedDnsConfig.defaults().put("custom","""{"servers":[{"tag":"a","type":"local"}],"final":"missing"}""")) }
    @Test fun onlyIncludedAppsRemoveExcludedBuilderConflict() { val s=state().put("appMode","include");s.getJSONArray("vpnApps").put("org.example.app");s.getJSONArray("excludedApps").put("org.example.other");val b=RouteRules.apply(base(),s,true).getJSONArray("inbounds").getJSONObject(0);assertTrue(b.has("include_package"));assertFalse(b.has("exclude_package")) }
    @Test fun groupedPresetOrderAndCombinedIds() {
        fun item(id:String,name:String,domains:Int,ips:Int)=PresetAssets.Item(id,name,"",1,domains,ips,1,"","x")
        val list=listOf(item("1111111111111111","YouTube",1,0),item("2222222222222222","YouTube",0,1),item("3333333333333333","Все кроме РФ",1,0),item("4444444444444444","Популярные сервисы 7",1,0))
        val grouped=RoutingDefaults.groups(list);assertEquals("Популярные сервисы 7",grouped[0].first);assertEquals("Все кроме РФ",grouped[1].first)
        val s=RouteRules.defaults();RoutingDefaults.addSelected(s,list,setOf("1111111111111111","2222222222222222"));assertEquals(1,s.getJSONArray("rules").length());assertEquals(2,s.getJSONArray("rules").getJSONObject(0).getJSONArray("presetIds").length())
    }
    @Test fun remoteMergePreservesLocalAndRussia() {
        val s=RouteRules.defaults().put("remoteRouting",RemoteRoutingConfig.defaults());s.getJSONArray("rules").put(rule("local.example")).put(RouteRules.outsideRussia("ru\n192.0.2.0/24"));s.getJSONObject("remoteRouting").getJSONArray("profiles").put(JSONObject().put("id","p").put("url","https://example.com/routes"))
        val document=JSONObject().put("version",1).put("rules",JSONArray().put(rule("remote.example","proxy")))
        val next=RemoteRoutingConfig.merge(s,"p",document,1234);assertEquals(2,s.getJSONArray("rules").length());assertEquals(3,next.getJSONArray("rules").length());assertEquals("outside-ru",next.getJSONArray("rules").getJSONObject(2).getString("builtinPreset"));assertEquals(1234,next.getJSONObject("remoteRouting").getJSONArray("profiles").getJSONObject(0).getLong("updatedAt"))
        assertEquals(3,RemoteRoutingConfig.merge(next,"p",document,2000).getJSONArray("rules").length())
    }
    @Test(expected=IllegalArgumentException::class) fun remoteCannotReplaceCountryRule() {RemoteRoutingConfig.merge(RouteRules.defaults(),"p",JSONObject().put("version",1).put("rules",JSONArray().put(RouteRules.outsideRussia("ru"))),0)}
    @Test fun remoteDueHonoursIntervalAndOptIn() {val s=RemoteRoutingConfig.defaults();val p=JSONObject().put("updatedAt",1000);assertFalse(RemoteRoutingConfig.due(p,s,Long.MAX_VALUE));s.put("autoRefresh",true).put("intervalMinutes",30);assertFalse(RemoteRoutingConfig.due(p,s,1800000));assertTrue(RemoteRoutingConfig.due(p,s,1801000))}
    @Test(expected=IllegalArgumentException::class) fun remoteRejectsCredentialUrl() {RemoteRoutingConfig.https("https://user:pass@example.com/x")}
    private fun varint(v:Long):ByteArray {var n=v;val b=ByteArrayOutputStream();while(n>127){b.write((n.toInt() and 127) or 128);n=n ushr 7};b.write(n.toInt());return b.toByteArray()}
    private fun blob(field:Int,data:ByteArray)=varint((field*8+2).toLong())+varint(data.size.toLong())+data
    private fun integer(field:Int,value:Int)=varint((field*8).toLong())+varint(value.toLong())
    @Test fun actualProtobufGeoIpConvertsCidr() {val cidr=blob(1,byteArrayOf(192.toByte(),0,2,0))+integer(2,24);val dat=blob(1,blob(1,"RU".toByteArray())+blob(2,cidr));assertEquals("192.0.2.0/24",GeoDataConfig.convert(dat,"geoip","ru").getJSONArray("rules").getJSONObject(0).getJSONArray("ip_cidr").getString(0))}
    @Test fun actualProtobufGeoSitePreservesMatchingTypes() {val domain=integer(1,2)+blob(2,"example.com".toByteArray());val dat=blob(1,blob(1,"test".toByteArray())+blob(2,domain));assertEquals("example.com",GeoDataConfig.convert(dat,"geosite","test").getJSONArray("rules").getJSONObject(0).getJSONArray("domain_suffix").getString(0))}
    @Test(expected=IllegalStateException::class) fun missingGeoCategoryRejected() {GeoDataConfig.convert(blob(1,blob(1,"RU".toByteArray())),"geosite","cn")}
    @Test(expected=IllegalArgumentException::class) fun truncatedGeoRejected() {GeoDataConfig.convert(byteArrayOf(10,100,0),"geosite","cn")}
    @Test fun domainStrategyResolvesAfterDnsHijack() {val c=RouteRules.apply(base(),state(),true);val a=c.getJSONObject("route").getJSONArray("rules");assertEquals("hijack-dns",a.getJSONObject(1).getString("action"));assertEquals("resolve",a.getJSONObject(2).getString("action"));assertEquals("prefer_ipv4",a.getJSONObject(2).getString("strategy"))}
    @Test fun encryptedBackupRetainsAllRoutingExtensions() {val s=state().put("remoteRouting",RemoteRoutingConfig.defaults()).put("geoData",GeoDataConfig.defaults());val password="routing-test-password".toCharArray();val restored=JSONObject(String(BackupCodec.decrypt(BackupCodec.encrypt(s.toString().toByteArray(),password),password)));RoutingStore.validate(restored);assertEquals(s.getJSONObject("advancedDns").toString(),restored.getJSONObject("advancedDns").toString());assertTrue(restored.has("remoteRouting"));assertTrue(restored.has("geoData"))}
    @Test fun throneLiteralRulesKeepExactDomain() {val d=JSONObject("""{"kind":"throne-route-profile","v":1,"rules":[{"name":"x","type":"simple_address_bypass","domain":["example.com"],"action":"route","outbound":"direct"}]}""");val r=RemoteRoutingImports.normalize(d).getJSONArray("rules").getJSONObject(0);assertEquals("direct",r.getString("target"));assertEquals("example.com",r.getJSONObject("nativeMatch").getJSONArray("domain").getString(0))}
    @Test(expected=IllegalArgumentException::class) fun throneUnsupportedConditionsRejectWholeProfile() {RemoteRoutingImports.normalize(JSONObject("""{"kind":"throne-route-profile","rules":[{"domain":["example.com"],"unknown_condition":"tcp"}]}"""))}
    @Test fun happDatasetReferencesAreLazyAndKeepCustomUrls() {val s=GeoDataConfig.fromProvider(JSONObject("""{"Geoipurl":"https://example.org/ip.dat","DirectIp":["geoip:ru"],"ProxySites":["geosite:google"]}"""));assertEquals("custom",s.getString("source"));assertEquals(2,s.getJSONArray("selectors").length());assertEquals("https://example.org/ip.dat",s.getString("geoipUrl"))}
    @Test fun retiredRemoteManagerInjectsNoDownloadsAndPreservesLegacyData(){val s=state().put("remoteRouting",RemoteRoutingConfig.defaults().put("adblock",true).put("autoRefresh",true));val before=s.toString();val config=RouteRules.apply(base(),s,true);assertFalse(config.getJSONObject("route").has("rule_set"));assertEquals(before,s.toString())}
    @Test fun ruAppDirectRuleCompilesAsPackageRouteWithoutBuilderExclusion(){val s=RoutingDefaultsRussianAppsPolicy.apply(state(),setOf("ru.oneme.app"),setOf("ru.oneme.app"),false);val config=RouteRules.apply(base(),s,true);val rules=config.getJSONObject("route").getJSONArray("rules");val direct=(0 until rules.length()).map {rules.getJSONObject(it)}.single {it.has("package_name")};assertEquals("direct",direct.getString("outbound"));assertEquals("ru.oneme.app",direct.getJSONArray("package_name").getString(0));assertFalse(config.getJSONArray("inbounds").getJSONObject(0).has("exclude_package"))}
    @Test(expected=IllegalArgumentException::class) fun providerMalformedArrayIsNotIgnored(){GeoDataConfig.fromProvider(JSONObject().put("DirectSites","geosite:ru"))}
    @Test(expected=IllegalArgumentException::class) fun providerExternalDatIsNotSilentlyDropped(){GeoDataConfig.fromProvider(JSONObject().put("DirectSites",JSONArray().put("ext:custom.dat:ru")))}
    @Test(expected=IllegalStateException::class) fun replacementMissingSelectedCategoryIsRejectedBeforeWrite(){val s=GeoDataConfig.defaults().put("selectors",JSONArray().put(JSONObject().put("kind","geosite").put("code","absent").put("target","direct")));val domain=integer(1,2)+blob(2,"example.com".toByteArray());val dat=blob(1,blob(1,"test".toByteArray())+blob(2,domain));GeoDataConfig.validateDownload(dat,"geosite",s)}
    @Test fun replacementSelectedCategoryIsValidated(){val s=GeoDataConfig.defaults().put("selectors",JSONArray().put(JSONObject().put("kind","geoip").put("code","ru").put("target","direct")));val cidr=blob(1,byteArrayOf(192.toByte(),0,2,0))+integer(2,24);val dat=blob(1,blob(1,"RU".toByteArray())+blob(2,cidr));GeoDataConfig.validateDownload(dat,"geoip",s)}
    @Test fun throneMixedConditionsPreserveConjunctionAndNetwork(){val d=JSONObject("""{"kind":"throne-route-profile","rules":[{"type":"custom","domain":["example.com"],"ip_cidr":["192.0.2.0/24"],"network":"tcp","port":443,"outbound":"direct"}]}""");val r=RemoteRoutingImports.normalize(d).getJSONArray("rules").getJSONObject(0);val s=state();s.getJSONArray("rules").put(r);val route=RouteRules.apply(base(),s,true).getJSONObject("route").getJSONArray("rules");val match=(0 until route.length()).map {route.getJSONObject(it)}.first {it.has("network")};assertEquals("tcp",match.getString("network"));assertTrue(match.has("domain")&&match.has("ip_cidr"));assertFalse(match.has("rules"));assertEquals(443,match.getInt("port"));assertEquals(1,RouteRules.apply(base(),s,true).getJSONObject("dns").getJSONArray("rules").length())}
    @Test fun nativeLogicalConditionsKeepInvertAndNestedApp(){val d=JSONObject("""{"kind":"throne-route-profile","rules":[{"type":"logical","mode":"and","invert":true,"rules":[{"domain_suffix":["example.com"]},{"package_name":["org.example.app"]}],"outbound":"proxy"}]}""");val r=RemoteRoutingImports.normalize(d).getJSONArray("rules").getJSONObject(0);val match=RouteRules.matches(r).getJSONObject(0);assertTrue(match.getBoolean("invert"));assertEquals("and",match.getString("mode"));assertTrue(RemoteRoutingImports.hasAppConditions(match))}
    @Test fun providerInlineAndGeoKeepExactOrderWithoutDuplicateRules(){val geo=GeoDataConfig.fromProvider(JSONObject("""{"RouteOrder":"direct-proxy-block","DirectSites":["full:first.example","geosite:ru","keyword:bank"],"ProxySites":["regexp:.*example$"],"BlockIp":["geoip:cn"]}"""));val s=state().put("geoData",geo);s.getJSONArray("rules").put(RouteRules.outsideRussia("ru"));val c=RouteRules.apply(base(),s,true,mapOf("geo-0" to "/fixture/ru.json","geo-1" to "/fixture/cn.json"));val a=c.getJSONObject("route").getJSONArray("rules");val selected=(0 until a.length()).map {a.getJSONObject(it)}.filter {it.has("domain")||it.has("rule_set")||it.has("domain_keyword")||it.has("domain_regex")};assertEquals(5,selected.size);assertEquals("first.example",selected[0].getJSONArray("domain").getString(0));assertEquals("mcrf-geo-0",selected[1].getJSONArray("rule_set").getString(0));assertEquals("bank",selected[2].getJSONArray("domain_keyword").getString(0));assertEquals("proxy",selected[3].getString("outbound"));assertEquals("reject",selected[4].getString("action"));assertEquals(2,c.getJSONObject("route").getJSONArray("rule_set").length())}
    @Test fun selectedProfileDnsSurvivesGeneratedGlobalAndMetadataIsConsumed(){val c=base().put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("type","udp").put("tag","wg-dns").put("server","192.0.2.53"))).put("final","wg-dns"));val expected=c.getJSONObject("dns").toString();DnsConfig.markProfileDns(c);DnsConfig.applyGenerated(c,DnsConfig.defaults());assertEquals(expected,c.getJSONObject("dns").toString());assertFalse(c.has(DnsConfig.PROFILE_DNS_MARKER))}
    @Test fun providerGeoIpDoesNotBecomeDomainDnsRule(){val s=state().put("geoData",GeoDataConfig.fromProvider(JSONObject("""{"BlockIp":["geoip:cn"]}""")));val c=RouteRules.apply(base(),s,true,mapOf("geo-0" to "/fixture/cn.json"));assertEquals(1,c.getJSONObject("dns").getJSONArray("rules").length())}
    @Test(expected=IllegalArgumentException::class) fun unknownThroneTypeCannotMasqueradeAsProxy(){RemoteRoutingImports.normalize(JSONObject("""{"kind":"throne-route-profile","rules":[{"type":"invented_proxy","domain":["example.com"]}]}"""))}
    @Test(expected=IllegalStateException::class) fun orphanBoundGeoSelectorRejected(){GeoDataConfig.validate(GeoDataConfig.defaults().put("selectors",JSONArray().put(JSONObject().put("kind","geosite").put("code","ru").put("target","direct").put("bound",true))))}
}
