package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RemainingRequestRegressionTest {
    @Test fun clashProcessRegexPreservesMihomosAndroidPackageSemantics() {
        assertEquals("(?i)discord",ClashRoutingProfile.match("PROCESS-NAME-REGEX","(?i)discord",emptyMap()).getJSONArray("package_name_regex").getString(0))
        assertEquals("chrome",ClashRoutingProfile.match("PROCESS-NAME","chrome",emptyMap()).getJSONArray("process_name").getString(0))
        val android=ClashRoutingProfile.match("PROCESS-NAME","com.discord",emptyMap())
        assertEquals("or",android.getString("mode"))
        assertEquals("com.discord",android.getJSONArray("rules").getJSONObject(1).getJSONArray("package_name").getString(0))
    }
    @Test fun sameVersionEditionChangesDoNotEvictTwoPreviousVersions() {
        val root=Files.createTempDirectory("mcrf-version-editions").toFile()
        try {
            val source=java.io.File(root,"input.apk");val store=VersionArchiveStore(java.io.File(root,"archive"));val id="org.example.module"
            for(code in 1..3){source.writeBytes(ByteArray(32){code.toByte()});store.capture(id,"v$code",code.toLong(),"$code",source,code.toLong()){}}
            for(edition in 4..7){source.writeBytes(ByteArray(32){edition.toByte()});store.capture(id,"v3",3,"edition-$edition",source,edition.toLong()){}}
            assertEquals(listOf(3L,2L,1L),store.entries(id).map {it.code})
            assertEquals(3,java.io.File(root,"archive/$id").listFiles()!!.count {it.extension=="apk"})
        }finally{root.deleteRecursively()}
    }
    @Test fun archiveSerializesDifferentStoreInstancesDuringActualCapture() {
        val root=Files.createTempDirectory("mcrf-archive-concurrency").toFile()
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        try {
            val first=java.io.File(root,"first.apk").apply {writeBytes(ByteArray(64){1})}
            val second=java.io.File(root,"second.apk").apply {writeBytes(ByteArray(64){2})}
            val directory=java.io.File(root,"archive");val id="org.example.module"
            val one=executor.submit<VersionArchiveStore.Entry>{VersionArchiveStore(directory).capture(id,"one",1,"one",first,1){entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}}
            check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val two=executor.submit<VersionArchiveStore.Entry>{VersionArchiveStore(directory).capture(id,"two",2,"two",second,2){}}
            assertThrows(java.util.concurrent.TimeoutException::class.java){two.get(100,java.util.concurrent.TimeUnit.MILLISECONDS)}
            release.countDown();one.get(5,java.util.concurrent.TimeUnit.SECONDS);two.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf(2L,1L),VersionArchiveStore(directory).entries(id).map {it.code})
        } finally {release.countDown();executor.shutdownNow();executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);root.deleteRecursively()}
    }
    private fun server(name:String)=ServerImport.read("socks://127.0.0.1:1080#$name").single()
    @Test fun autoFilterUsesExitProofAndNeverNameFlagForCountry() {
        val ru=server("DE-premium");val de=server("RU-test");val unknown=server("DE-unknown")
        val proofs=JSONObject().put(ru.id,JSONObject().put("country","RU").put("at",1))
            .put(de.id,JSONObject().put("country","DE").put("at",2))
        val filter=AutoExclusions.parse("%ru")
        assertFalse(AutoExclusions.allowed(ru,filter,proofs));assertTrue(AutoExclusions.allowed(de,filter,proofs))
        assertFalse(AutoExclusions.allowed(unknown,filter,proofs))
        assertTrue(AutoExclusions.allowed(unknown,AutoExclusions.parse(""),proofs))
        assertFalse(AutoExclusions.allowed(de,AutoExclusions.parse("TEST"),proofs))
        assertThrows(IllegalArgumentException::class.java){AutoExclusions.parse("%UNKNOWN")}
    }
    @Test fun exitCountryRequiresRealIpAndUnambiguousTrace() {
        assertEquals("DE",AutoExclusions.country("ip=203.0.113.7\nloc=DE\n"))
        assertThrows(IllegalArgumentException::class.java){AutoExclusions.country("ip=hostname.invalid\nloc=DE\n")}
        assertThrows(IllegalArgumentException::class.java){AutoExclusions.country("ip=203.0.113.7\nloc=DE\nloc=RU\n")}
    }
    @Test fun exitProofClearsWhenServerSettingsChangeButFilterDoesNotEraseMarks() {
        val entry=server("test");val c=ConnectionCatalogue("");c.add(entry.original);c.setAuto(entry.id,true)
        c.recordExitCountry(entry.id,"DE",1);c.setAutoExclusions("%RU")
        assertEquals(1,c.autoCandidates(c.entries()).size);assertEquals(setOf(entry.id),c.autoIds())
        c.clearExitCountry(entry.id);assertTrue(c.autoCandidates(c.entries()).isEmpty());assertEquals(setOf(entry.id),c.autoIds())
        assertEquals("%RU",ConnectionCatalogue(c.serialize()).autoExclusions())
    }
    @Test fun archiveKeepsThreePerPackageAndFailedVerificationPreservesAllPriorEntries() {
        val root=Files.createTempDirectory("mcrf-version-test").toFile()
        try {
            val source=java.io.File(root,"input.apk");val store=VersionArchiveStore(java.io.File(root,"archive"));val id="org.example.module"
            for(n in 1..4){source.writeBytes(ByteArray(64){n.toByte()});store.capture(id,"v$n",n.toLong(),"receipt$n",source,n.toLong()){}}
            assertEquals(listOf(4L,3L,2L),store.entries(id).map {it.code})
            val before=store.entries(id)
            source.writeBytes(ByteArray(64){9});assertThrows(IllegalArgumentException::class.java){store.capture(id,"bad",9,"bad",source,9){throw IllegalArgumentException("signature")}}
            assertEquals(before,store.entries(id));before.forEach {assertTrue(store.file(it).isFile)}
            val second=store.capture("org.example.other","other",1,"other",source,10){}
            assertEquals(3,store.entries(id).size);assertTrue(store.file(second).isFile)
            store.file(before.first()).writeBytes(ByteArray(64){0})
            assertThrows(IllegalArgumentException::class.java){store.file(before.first())}
            assertThrows(IllegalArgumentException::class.java){store.entries("../escape")}
        }finally {root.deleteRecursively()}
    }
    @Test fun roscomMihomoPreservesOrderGroupsAndProvidersWithoutFetchingProxySubscriptions() {
        val yaml=javaClass.getResource("/routing/roscomvpn-mihomo.yaml")!!.readText()
        val parsed=ImportedRoutingProfile.parse(yaml)
        val state=RouteRules.defaults();val id=RoutingGroups.add(state,parsed.name,parsed.settings);RoutingGroups.select(state,id);RoutingStore.validate(state)
        val rules=state.getJSONArray("rules");val providers=ImportedRuleProviders.rows(state.getJSONObject("geoData"))
        assertTrue(providers.size>20);assertEquals("direct",rules.getJSONObject(0).getString("target"))
        assertEquals("block",rules.getJSONObject(1).getString("target"));assertEquals("logical",rules.getJSONObject(2).getJSONObject("nativeMatch").getString("type"))
        val steam=providers.single {it.optString("url").endsWith("/steam.mrs")}.getString("tag")
        assertEquals("direct",(0 until rules.length()).map(rules::getJSONObject).single {it.getJSONObject("nativeMatch").optJSONArray("rule_set")?.optString(0)==steam}.getString("target"))
        assertEquals("proxy",state.getString("defaultTarget"))
        assertEquals("DoH",state.getJSONObject("globalDns").getString("protocol"))
    }
    @Test fun yamlConstructorsDuplicatesCyclesAndUnknownRulesFailBeforeCommit() {
        assertThrows(Exception::class.java){RoutingYaml.parse("x: !!java.net.URL [https://example.com]")}
        assertThrows(Exception::class.java){RoutingYaml.parse("x: 1\nx: 2\n")}
        assertThrows(Exception::class.java){RoutingYaml.parse("x: &a [*a]\n")}
        assertThrows(Exception::class.java){ImportedRoutingProfile.parse("dns: {nameserver: [1.1.1.1]}\nrules: [\"UNKNOWN,example.com,DIRECT\",\"MATCH,PROXY\"]")}
    }
    @Test fun subscriptionRoutingExtractsBeforeServerParserAndStaysOwnedBySubscription() {
        val json=javaClass.getResource("/routing/roscomvpn-incy.json")!!.readText()
        val link="incy://routing/onadd/"+java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val entry=server("owned");val extracted=SubscriptionRouting.extract("$link\n${entry.original}",null,null)
        assertEquals(link,extracted.request);assertEquals(listOf(entry.id),ServerImport.read(extracted.servers).map {it.id})
        val parsed=ImportedRoutingProfile.parse(link)
        val sub=JSONObject().put("id","subscription-a").put("servers",JSONArray().put(entry.original))
            .put("metadata",JSONObject().put("routing",JSONObject().put("name",parsed.name).put("settings",parsed.settings)))
        val original=sub.toString();val state=RouteRules.defaults();val ordinary=state.toString()
        val projected=SubscriptionRouting.project(state,entry.id,listOf(sub))
        assertEquals("8.8.8.8",DnsConfig.build(projected.getJSONObject("globalDns"),"d","b").getString("server"))
        assertTrue(projected.getJSONObject("geoData").getString("cacheNamespace").startsWith("profile-"))
        assertEquals(original,sub.toString());assertEquals(ordinary,state.toString())
        assertEquals(ordinary,SubscriptionRouting.project(state,"missing",listOf(sub)).toString())
        assertEquals(parsed.name,SubscriptionRouting.effectiveProfile(state,entry.id,listOf(sub))?.second?.getString("name"))
        assertNull(SubscriptionRouting.effectiveProfile(state,"missing",listOf(sub)))
        val group=RoutingGroups.add(state,parsed.name,parsed.settings)
        RoutingGroups.select(state,group)
        assertNull(SubscriptionRouting.effectiveProfile(state,entry.id,listOf(sub)))
    }
    @Test fun terminalDecorationsDoNotLeakIntoFailureMessage() {
        assertEquals("Попробуйте через 10–20 минут.",ConnectionPresentation.cleanFailure("\u001b[31mПопробуйте через 10–20 минут. · \u001b[0m"))
    }
    @Test fun publicMrsDomainAndAddressDocumentsDecodeToNativeSourceRules() {
        val domains=MihomoMrs.decode(javaClass.getResourceAsStream("/routing/roscom-steam.mrs")!!.readBytes(),"domain").getJSONArray("rules")
        assertTrue(domains.length()>0)
        val all=domains.toString();assertTrue(all.contains("steampowered.com"));assertTrue(all.contains("steamcommunity.com"))
        val ips=MihomoMrs.decode(javaClass.getResourceAsStream("/routing/metacubex-telegram-ip.mrs")!!.readBytes(),"ipcidr").getJSONArray("rules")
        assertTrue(ips.length()>0);repeat(ips.length()){RemoteRoutingImports.validateMatch(ips.getJSONObject(it))}
        assertThrows(Exception::class.java){MihomoMrs.decode(byteArrayOf(0,1,2),"domain")}
        assertThrows(Exception::class.java){MihomoMrs.decode(javaClass.getResourceAsStream("/routing/roscom-steam.mrs")!!.readBytes(),"ipcidr")}
    }
    @Test fun routingDownloadBudgetExpiresBeforeOpeningAnotherProvider() {
        var opened=false
        assertThrows(java.net.SocketTimeoutException::class.java) {
            RemoteRoutingConfig.fetchWithin("https://example.com/rules",1024,System.nanoTime()-1){opened=true;error("must not open")}
        }
        assertFalse(opened)
    }
    @Test fun staleIpProbeCannotPublishCountryOrEraseTheNewServersProof() {
        val entry=server("old");val c=ConnectionCatalogue("");c.add(entry.original)
        c.edit(entry.id,ConnectionEdits.snapshot(entry).put("name","changed"))
        assertThrows(IllegalArgumentException::class.java){c.recordExitCountry(entry,"RU",1)}
        val fresh=c.entries().single();c.recordExitCountry(fresh,"DE",2)
        c.clearExitCountry(entry)
        assertEquals("DE",c.exitTests().getJSONObject(entry.id).getString("country"))
    }
    @Test fun routingResourceLinksRemainHttpsAndInlineDocumentsStayOffline() {
        val url="https://example.com/profile.json?version=2"
        assertEquals(url,HappRoutingLinks.resource("happ://routing/add/$url"))
        assertEquals(url,HappRoutingLinks.resource("incy://routing/onadd/"+java.net.URLEncoder.encode(url,"UTF-8")))
        assertEquals(url,HappRoutingLinks.resource(url))
        assertNull(HappRoutingLinks.resource("incy://routing/off"))
        assertNull(HappRoutingLinks.resource("happ://routing/add/e30="))
        assertThrows(IllegalArgumentException::class.java){HappRoutingLinks.resource("happ://routing/unknown/$url")}
    }
    @Test fun subscriptionPolicyCannotLeakAcrossAutomaticPoolsOrGlobalBindings() {
        val one=server("one");val two=server("two")
        val parsed=ImportedRoutingProfile.parse(javaClass.getResource("/routing/roscomvpn-incy.json")!!.readText())
        fun sub(id:String,entry:ServerImport.Server)=JSONObject().put("id",id).put("servers",JSONArray().put(entry.original))
            .put("metadata",JSONObject().put("routing",JSONObject().put("name",parsed.name).put("settings",parsed.settings)))
        val subscriptions=listOf(sub("a",one),sub("b",two))
        val state=RouteRules.defaults().put(RoutingConnectionBinding.KEY,JSONObject().put("enabled",true).put("vpn",JSONArray().put(two.id)))
        assertThrows(IllegalArgumentException::class.java){SubscriptionRouting.projectPool(state,listOf(one.id,two.id),subscriptions)}
        val projected=SubscriptionRouting.projectPool(state,listOf(one.id),subscriptions)
        assertFalse(projected.has(RoutingConnectionBinding.KEY))
        assertTrue(state.has(RoutingConnectionBinding.KEY))
        assertFalse(parsed.settings.getJSONObject("geoData").has("cacheNamespace"))
    }
}
