package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Source only until the WHOLE P2 batch is ready. No network, modules or TUN. */
class RouteServerPlanTest {
    private fun server(name:String,type:String="socks")=ServerImport.Server("owned-route-$name-$type",name,type,"$name.example.test",443,
        JSONObject().put("type",type).put("server","$name.example.test").put("server_port",443),null,null)
    private fun rule(id:String,host:String,entry:ServerImport.Server?=null)=JSONObject().put("id",id).put("name",id)
        .put("text",host).put("mode","addresses").put("target","proxy").put("enabled",true)
        .apply {entry?.let {put(RouteServerPlan.KEY,it.id)}}
    private fun state(vararg rules:JSONObject)=RouteRules.defaults().put("rules",JSONArray(rules.toList()))
    private fun base(main:ServerImport.Server):JSONObject {
        val config=ServerImport.build(listOf(main),main.id,false,60,50,"Cloudflare")
        DnsConfig.applyGenerated(config,DnsConfig.defaults())
        return ProfileConfig.build(config.toString(),true,10808)
    }
    private fun nodes(config:JSONObject,key:String)=config.optJSONArray(key)?.let {a->(0 until a.length()).map {a.getJSONObject(it)}}?:emptyList()
    private fun routed(config:JSONObject,host:String)=nodes(config.getJSONObject("route"),"rules")
        .single {it.optJSONArray("domain_suffix")?.optString(0)==host}

    @Test fun rulePoolUsesOnlyItsMembersAndDnsFollowsTheSameGroup() {
        val main=server("main");val first=server("first");val second=server("second")
        val pool=rule("pool","pool.example.test").put(RouteServerPlan.POOL,JSONArray().put(first.id).put(second.id))
        val same=rule("same","same.example.test").put(RouteServerPlan.POOL,JSONArray().put(second.id).put(first.id))
        val config=RouteRules.apply(base(main),state(pool,same),true,routeServers=listOf(main,first,second))
        val tag=RouteServerPlan.outbound(pool,"")
        assertEquals(tag,RouteServerPlan.outbound(same,""))
        val output=nodes(config,"outbounds").single {it.getString("tag")==tag}
        assertEquals("urltest",output.getString("type"))
        assertEquals(setOf(RouteServerPlan.tag(first.id),RouteServerPlan.tag(second.id)),output.getJSONArray("outbounds").let {a->(0 until a.length()).map {a.getString(it)}.toSet()})
        assertEquals(tag,routed(config,"pool.example.test").getString("outbound"))
        assertTrue(nodes(config.getJSONObject("dns"),"servers").any {it.optString("detour")==tag})
        assertEquals(1,nodes(config,"inbounds").count {it.optString("type")=="tun"})
    }
    @Test fun invalidPoolCannotFallbackToMainOrDirect() {
        val first=server("first");val second=server("second")
        val pool=rule("pool","pool.test").put(RouteServerPlan.POOL,JSONArray().put(first.id).put(second.id))
        assertThrows(IllegalArgumentException::class.java){RouteServerPlan(state(pool),listOf(first))}
        assertThrows(IllegalArgumentException::class.java){RouteServerPlan.validate(JSONObject(pool.toString()).put(RouteServerPlan.KEY,first.id))}
        assertThrows(IllegalArgumentException::class.java){RouteServerPlan.validate(JSONObject(pool.toString()).put("target","direct"))}
        assertThrows(IllegalArgumentException::class.java){RouteServerPlan.validate(JSONObject(pool.toString()).put(RouteServerPlan.POOL,JSONArray().put(first.id).put(first.id)))}
    }
    @Test fun inactiveRuleGroupsNeverStartTheirAdditionalServers() {
        val first=server("first");val settings=state(rule("other","other.test",first))
        RoutingGroups.ensure(settings);RoutingGroups.select(settings,"bs")
        assertTrue(RouteServerPlan.activeIds(settings).isEmpty())
        assertTrue(RouteServerPlan(settings,emptyList()).entries.isEmpty())
        RoutingGroups.select(settings,"vpn")
        assertEquals(listOf(first.id),RouteServerPlan.activeIds(settings))
    }

    @Test fun twoRulesUseDifferentServersAndKeepMainFinalAndOneTun() {
        val main=server("main");val first=server("first");val second=server("second","trojan")
        val settings=state(rule("a","a.example.test",first),rule("b","b.example.test",second),rule("default","rest.example.test"))
        val source=base(main);val beforeSource=source.toString();val beforeSettings=settings.toString()
        val config=RouteRules.apply(source,settings,true,routeServers=listOf(first,main,second))
        assertEquals(RouteServerPlan.tag(first.id),routed(config,"a.example.test").getString("outbound"))
        assertEquals(RouteServerPlan.tag(second.id),routed(config,"b.example.test").getString("outbound"))
        assertEquals(source.getJSONObject("route").getString("final"),routed(config,"rest.example.test").getString("outbound"))
        assertEquals(source.getJSONObject("route").getString("final"),config.getJSONObject("route").getString("final"))
        assertEquals(1,nodes(config,"inbounds").count {it.optString("type")=="tun"})
        assertEquals(beforeSource,source.toString());assertEquals(beforeSettings,settings.toString())
        assertEquals(listOf("a.example.test","b.example.test","rest.example.test"),nodes(config.getJSONObject("route"),"rules")
            .mapNotNull {it.optJSONArray("domain_suffix")?.optString(0)})
    }
    @Test fun domainAndOriginalAppDnsUseTheSameSelectedServerNotGlobalProxy() {
        val main=server("main");val other=server("other")
        val app=rule("apps","app:org.mcrf.fixture.one",other).put("mode","apps")
        val config=RouteRules.apply(base(main),state(rule("domain","site.example.test",other),app),true,routeServers=listOf(other))
        val dns=config.getJSONObject("dns");val servers=nodes(dns,"servers").associateBy {it.getString("tag")}
        for(key in listOf("domain_suffix","package_name")) {
            val match=nodes(dns,"rules").single {it.has(key)}
            assertEquals(RouteServerPlan.tag(other.id),servers.getValue(match.getString("server")).getString("detour"))
        }
        assertEquals(1,servers.values.count {it.optString("detour")==RouteServerPlan.tag(other.id)})
    }
    @Test fun explicitListDnsFollowsItsServerAndDirectRuleNeverAcquiresIt() {
        val main=server("main");val other=server("other")
        val explicit=rule("dns","dns.example.test",other).put("dns",DnsConfig.defaults())
        val direct=rule("direct","direct.example.test").put("target","direct")
        val config=RouteRules.apply(base(main),state(explicit,direct),true,routeServers=listOf(other))
        val dns=config.getJSONObject("dns");val match=nodes(dns,"rules").single {it.optString("server").startsWith("dns-list-")}
        assertEquals(RouteServerPlan.tag(other.id),nodes(dns,"servers").single {it.getString("tag")==match.getString("server")}.getString("detour"))
        assertEquals("direct",routed(config,"direct.example.test").getString("outbound"))
        assertEquals("mcrf-dns-direct",nodes(dns,"rules").single {it.optJSONArray("domain_suffix")?.optString(0)=="direct.example.test"}.getString("server"))
    }
    @Test fun missingServerFailsClosedButDisabledReferenceDoesNotStartAnything() {
        val missing=server("missing");val settings=state(rule("a","a.example.test",missing))
        assertThrows(IllegalArgumentException::class.java){RouteServerPlan(settings,emptyList())}
        settings.getJSONArray("rules").getJSONObject(0).put("enabled",false)
        assertTrue(RouteServerPlan(settings,emptyList()).entries.isEmpty())
    }
    @Test fun sharedServerIsStartedOnceAndSnapshotsAreDefensive() {
        val entry=server("same");val settings=state(rule("a","a.test",entry),rule("b","b.test",entry))
        val plan=RouteServerPlan(settings,listOf(entry));assertEquals(1,plan.entries.size)
        entry.outbound!!.put("server","changed.invalid")
        plan.entries.single().outbound!!.put("server","caller.invalid")
        val config=plan.append(base(server("main")))
        assertEquals("same.example.test",nodes(config,"outbounds").single {it.optString("tag")==RouteServerPlan.tag(entry.id)}.getString("server"))
    }
    @Test fun fullStandaloneConfigAndExcludedEngineAreNotSilentlyFlattened() {
        val full=ServerImport.Server("owned-full","full","sing-box","",0,null,null,JSONObject().put("route",JSONObject()))
        val excluded=ServerImport.Server("owned-excluded","excluded","openflux","",0,null,null,null,JSONObject().put("type","openflux"))
        for(entry in listOf(full,excluded)) {
            assertFalse(RouteServerPlan.selectable(entry))
            assertThrows(IllegalArgumentException::class.java){RouteServerPlan(state(rule("a","a.test",entry)),listOf(entry))}
        }
    }
    @Test fun carrierHasOnlyPrivateSocksPlaceholderAndEndpointAddsNoTun() {
        val carrier=ServerImport.Server("owned-carrier","carrier","wdtt","",0,null,null,null,JSONObject().put("type","wdtt"))
        val wg=ServerImport.Server("owned-wg","wg","wireguard","192.0.2.1",443,null,JSONObject().put("type","wireguard").put("system",false),null)
        val plan=RouteServerPlan(state(rule("a","a.test",carrier),rule("b","b.test",wg)),listOf(carrier,wg))
        val config=plan.append(base(server("main")))
        val placeholder=nodes(config,"outbounds").single {it.optString("tag")==RouteServerPlan.tag(carrier.id)}
        assertEquals("127.0.0.1",placeholder.getString("server"));assertEquals(1,placeholder.getInt("server_port"))
        assertEquals("socks",placeholder.getString("type"));assertFalse(placeholder.has("password"))
        assertEquals(RouteServerPlan.tag(wg.id),nodes(config,"endpoints").single().getString("tag"))
        assertEquals(1,nodes(config,"inbounds").count {it.optString("type")=="tun"})
    }
    @Test fun malformedIdAndDirectOrBlockServerOverridesAreRejected() {
        val entry=server("one")
        for(target in listOf("direct","block"))assertThrows(IllegalArgumentException::class.java){RouteServerPlan.validate(rule("a","a.test",entry).put("target",target))}
        for(value in listOf<Any>("",entry.id.uppercase(),true,7,JSONObject.NULL))
            assertThrows(IllegalArgumentException::class.java){RouteServerPlan.validate(rule("a","a.test").put(RouteServerPlan.KEY,value))}
    }
    @Test fun collidingTagFailsWithoutEditingInputAndSingleServerBackendsRefuseOverrides() {
        val entry=server("one");val settings=state(rule("a","a.test",entry));val source=base(server("main"))
        source.getJSONArray("outbounds").put(JSONObject().put("type","direct").put("tag",RouteServerPlan.tag(entry.id)))
        val before=source.toString()
        assertThrows(IllegalArgumentException::class.java){RouteServerPlan(settings,listOf(entry)).append(source)}
        assertEquals(before,source.toString())
        assertThrows(IllegalArgumentException::class.java){RouteServerPlan.requireSingleServerCore(settings)}
        RouteServerPlan.requireSingleServerCore(state(rule("normal","a.test")))
    }
    @Test fun referenceSurvivesJsonRoundtripAndDoesNotAlterGlobalPolicies() {
        val entry=server("one");val settings=state(rule("a","a.test",entry)).put("excludedApps",JSONArray().put("org.mcrf.fixture.direct"))
        val restored=JSONObject(settings.toString());RoutingStore.validate(restored)
        assertEquals(entry.id,restored.getJSONArray("rules").getJSONObject(0).getString(RouteServerPlan.KEY))
        assertEquals("org.mcrf.fixture.direct",restored.getJSONArray("excludedApps").getString(0))
        assertEquals("proxy",restored.getString("defaultTarget"))
    }
    @Test fun differentProtocolBackendsKeepTheirOwnRuleAndDnsAfterBridgeReplacement() {
        val main=server("main")
        val xray=ServerImport.Server("owned-native-xray","xray","xray","xray.example.test",443,
            JSONObject().put("type","xray").put("outbound",JSONObject()
                .put("protocol","socks").put("settings",JSONObject().put("servers",JSONArray().put(
                    JSONObject().put("address","xray.example.test").put("port",443))))),null,null)
        val tls=ServerImport.Server("owned-tls-core","tls","trojan","tls.example.test",443,
            JSONObject().put("type","trojan").put("server","tls.example.test").put("server_port",443)
                .put("password","owned-fixture-password").put("tls",JSONObject().put("enabled",true))
                .put("tls_fragment",JSONObject().put("enabled",true).put("size","10-20").put("sleep","0-1")),null,null)
        val ordinary=server("ordinary")
        val settings=state(rule("xray","xray.test",xray),rule("tls","tls.test",tls),rule("plain","plain.test",ordinary))
        val source=RouteRules.apply(base(main),settings,true,routeServers=listOf(xray,tls,ordinary))
        val before=source.toString()
        val optional=OptionalCorePlan(source,OptionalCoreSettings())
        assertEquals(mapOf(RouteServerPlan.tag(xray.id) to "xray",RouteServerPlan.tag(tls.id) to "tls-core"),
            optional.entries().associate {it.tag to it.request.kind})
        val replaced=optional.replace(optional.entries().associate {entry->entry.tag to
            OptionalCoreConfig.bridgeOutbound(entry.tag,1080,"owned-fixture-user","owned-fixture-password",entry.request.kind)})
        assertEquals(source.getJSONObject("route").toString(),replaced.getJSONObject("route").toString())
        assertEquals(source.getJSONObject("dns").toString(),replaced.getJSONObject("dns").toString())
        for((host,entry) in listOf("xray.test" to xray,"tls.test" to tls,"plain.test" to ordinary))
            assertEquals(RouteServerPlan.tag(entry.id),routed(replaced,host).getString("outbound"))
        assertEquals("ordinary.example.test",nodes(replaced,"outbounds").single {it.optString("tag")==RouteServerPlan.tag(ordinary.id)}.getString("server"))
        assertEquals(1,nodes(replaced,"inbounds").count {it.optString("type")=="tun"})
        assertEquals(before,source.toString())
    }
    @Test fun dependentOutputsAndSystemEndpointsCannotImpersonateAnIndependentServer() {
        val independent=server("one")
        for(type in listOf("direct","block","dns","selector","urltest")) {
            val invalid=server("one",type)
            assertFalse(RouteServerPlan.selectable(invalid))
        }
        independent.outbound!!.put("detour","main-profile-exit")
        assertFalse(RouteServerPlan.selectable(independent))
        val system=ServerImport.Server("owned-system-wg","wg","wireguard","192.0.2.1",443,null,
            JSONObject().put("type","wireguard").put("system",true),null)
        assertFalse(RouteServerPlan.selectable(system))
        val conflicting=ServerImport.Server("owned-conflicting","conflict","socks","one.example.test",443,
            server("one").outbound,JSONObject().put("type","wireguard"),null)
        assertFalse(RouteServerPlan.selectable(conflicting))
        assertThrows(IllegalArgumentException::class.java){RouteServerPlan(state(rule("a","a.test",system)),listOf(system))}
    }
}
