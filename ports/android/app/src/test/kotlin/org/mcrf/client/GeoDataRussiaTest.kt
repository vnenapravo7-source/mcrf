package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class GeoDataRussiaTest {
    private fun varint(value:Int):ByteArray {
        val out=ByteArrayOutputStream();var n=value
        while(n>127){out.write((n and 127) or 128);n=n ushr 7};out.write(n);return out.toByteArray()
    }
    private fun blob(field:Int,bytes:ByteArray)=varint(field*8+2)+varint(bytes.size)+bytes
    private fun site(code:String,domain:String,type:Int=2)=blob(1,blob(1,code.toByteArray())+blob(2,varint(8)+varint(type)+blob(2,domain.toByteArray())))
    private fun selector(kind:String="geosite",code:String="ru")=JSONObject().put("kind",kind).put("code",code).put("target","direct")
    private fun russia()=GeoDataPresets.items.single {it.kind=="geosite"&&it.code=="ru"}
    @Test fun geositeRuResolvesStandardCategoryAndValidatesDownload() {
        val bytes=site("CATEGORY-RU","ru")
        val settings=GeoDataConfig.defaults().put("selectors",JSONArray().put(selector()))
        GeoDataConfig.validateDownload(bytes,"geosite",settings)
        assertEquals("ru",GeoDataConfig.convert(bytes,"geosite","RU").getJSONArray("rules").getJSONObject(0).getJSONArray("domain_suffix").getString(0))
    }
    @Test fun customLiteralRuHasPriorityRegardlessOfCategoryOrder() {
        for(bytes in listOf(site("category-ru","fallback.test")+site("ru","custom.test"),site("ru","custom.test")+site("category-ru","fallback.test"))) {
            assertEquals("custom.test",GeoDataConfig.convert(bytes,"geosite","ru").getJSONArray("rules").getJSONObject(0).getJSONArray("domain_suffix").getString(0))
        }
    }
    @Test fun aliasDoesNotHideMissingCategoriesOrApplyToGeoip() {
        assertThrows(IllegalStateException::class.java){GeoDataConfig.convert(site("category-cn","cn"),"geosite","ru")}
        assertThrows(IllegalStateException::class.java){GeoDataConfig.convert(site("category-ru","ru"),"geoip","ru")}
    }
    @Test fun providerAliasIsCanonicalBeforeEitherCorePreparesTheDataset() {
        val geo=GeoDataConfig.fromProvider(JSONObject().put("DirectSites",JSONArray().put("geosite:ru")).put("DirectIp",JSONArray().put("geoip:ru")))
        assertEquals("category-ru",geo.getJSONArray("selectors").getJSONObject(0).getString("code"))
        assertEquals("ru",geo.getJSONArray("selectors").getJSONObject(1).getString("code"))
        assertEquals("mcrf-geo-0",geo.getJSONArray("providerRules").getJSONObject(0).getJSONObject("nativeMatch").getJSONArray("rule_set").getString(0))
    }
    @Test fun customDatasetSourceAndCategoryArePreserved() {
        val url="https://example.org/custom.dat"
        val geo=GeoDataConfig.fromProvider(JSONObject().put("Geositeurl",url).put("DirectSites",JSONArray().put("geosite:ru")))
        assertEquals(url,geo.getString("geositeUrl"));assertEquals("ru",geo.getJSONArray("selectors").getJSONObject(0).getString("code"))
    }
    @Test fun selectorPickerCanonicalizesAliasWithoutEditingCaller() {
        val source=RouteRules.defaults();val wanted=JSONArray().put(selector())
        val next=GeoDataPresets.selectors(source,wanted)
        assertFalse(source.has("geoData"));assertEquals("ru",wanted.getJSONObject(0).getString("code"))
        assertEquals("category-ru",next.getJSONObject("geoData").getJSONArray("selectors").getJSONObject(0).getString("code"))
        assertThrows(IllegalArgumentException::class.java){GeoDataPresets.selectors(source,JSONArray().put(selector()).put(selector(code="CATEGORY-RU")))}
    }
    @Test fun russiaPresetCreatesVisibleLazyRuleAndReusesLegacyAlias() {
        val state=RouteRules.defaults().put("geoData",GeoDataConfig.defaults().put("selectors",JSONArray().put(selector())))
        GeoDataPresets.addRules(state,listOf(russia()),"direct")
        GeoDataPresets.addRules(state,listOf(russia()),"proxy")
        RoutingStore.validate(state)
        val selectors=state.getJSONObject("geoData").getJSONArray("selectors")
        assertEquals(1,selectors.length());assertEquals("category-ru",selectors.getJSONObject(0).getString("code"))
        assertEquals(setOf(0),GeoDataConfig.neededSelectors(state))
        val rule=state.getJSONArray("rules").getJSONObject(0)
        assertEquals(1,state.getJSONArray("rules").length());assertEquals("proxy",rule.getString("target"))
        assertEquals("geosite:ru",rule.getString("geoPreset"))
        assertEquals("mcrf-geo-0",RouteRules.matches(rule).getJSONObject(0).getJSONArray("rule_set").getString(0))
        rule.put("enabled",false);assertTrue(GeoDataConfig.neededSelectors(state).isEmpty())
    }
    @Test fun selectorLimitFailureLeavesRulesAndGeodataUnchanged() {
        val selectors=JSONArray();repeat(64){selectors.put(selector(code="fixture-$it"))}
        val state=RouteRules.defaults().put("geoData",GeoDataConfig.defaults().put("selectors",selectors));val before=state.toString()
        assertThrows(IllegalArgumentException::class.java){GeoDataPresets.addRules(state,listOf(russia()),"direct")}
        assertEquals(before,state.toString())
    }
    @Test fun countryDomainAndIpRulesAttachOnceAndKeepDomainPriority() {
        val state=RouteRules.defaults()
        GeoDataPresets.addRules(state,listOf(russia(),GeoDataPresets.items.single {it.kind=="geoip"&&it.code=="ru"}),"direct")
        val base=JSONObject("""{"route":{"rules":[],"final":"proxy"},"outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1080}],"inbounds":[]}""")
        val compiled=RouteRules.apply(base,state,true,mapOf("geo-0" to "/fixture/domains.json","geo-1" to "/fixture/ip.json"))
        val route=compiled.getJSONObject("route");assertEquals(2,route.getJSONArray("rule_set").length())
        val rules=route.getJSONArray("rules")
        val ordered=(0 until rules.length()).map {rules.getJSONObject(it)}
        val domain=ordered.indexOfFirst {it.optJSONArray("rule_set")?.optString(0)=="mcrf-geo-0"}
        val resolve=ordered.indexOfFirst {it.optString("action")=="resolve"}
        val ip=ordered.indexOfFirst {it.optJSONArray("rule_set")?.optString(0)=="mcrf-geo-1"}
        assertTrue(domain>=0&&domain<resolve&&resolve<ip)
        assertEquals(1,ordered.count {it.optJSONArray("rule_set")?.optString(0)=="mcrf-geo-0"})
        assertEquals(1,ordered.count {it.optJSONArray("rule_set")?.optString(0)=="mcrf-geo-1"})
        assertEquals("direct",ordered[domain].getString("outbound"));assertEquals("direct",ordered[ip].getString("outbound"))
    }
    @Test fun addingRussiaDoesNotRewritePersonalRuleOrCustomSource() {
        val personal=JSONObject().put("id","personal").put("name","Personal").put("text","example.test").put("target","block").put("mode","addresses")
        val state=RouteRules.defaults().put("geoData",GeoDataConfig.defaults().put("source","custom").put("geositeUrl","https://example.org/ru.dat"))
        state.getJSONArray("rules").put(personal);val before=personal.toString()
        GeoDataPresets.addRules(state,listOf(russia()),"direct")
        assertEquals(before,state.getJSONArray("rules").getJSONObject(0).toString())
        assertEquals("https://example.org/ru.dat",state.getJSONObject("geoData").getString("geositeUrl"))
        assertEquals("ru",state.getJSONObject("geoData").getJSONArray("selectors").getJSONObject(0).getString("code"))
    }
    @Test fun allDomainMatchingTypesRemainDistinct() {
        for((type,key) in listOf("domain_keyword","domain_regex","domain_suffix","domain").withIndex()) {
            val rule=GeoDataConfig.convert(site("test","example.test",type),"geosite","test").getJSONArray("rules").getJSONObject(0)
            assertEquals(1,rule.length());assertEquals("example.test",rule.getJSONArray(key).getString(0))
        }
    }
    @Test fun geoipKeepsCidrAndInverseFlag() {
        val cidr=blob(1,byteArrayOf(192.toByte(),0,2,0))+varint(16)+varint(24)
        val bytes=blob(1,blob(1,"RU".toByteArray())+blob(2,cidr)+varint(24)+varint(1))
        val rule=GeoDataConfig.convert(bytes,"geoip","ru").getJSONArray("rules").getJSONObject(0)
        assertEquals("192.0.2.0/24",rule.getJSONArray("ip_cidr").getString(0));assertTrue(rule.getBoolean("invert"))
    }
}
