package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BsNativeRoutingSnapshotTest {
    @Test fun ownedPhysicalServiceCapturesExplicitBlockFallbackWithoutEmptyMatchers() {
        val source=BsNativePhysicalServiceFixture.fixtureSource(JSONObject()).apply {remove("mcrf_carrier")}
        val config=ProfileConfig.build(source.toString(),true,10808)
        val policy=BsNativeRoutingSnapshot.capture(config,"carrier",emptyMap()).policy()
        assertEquals("block",policy.getString("defaultTarget"))
        val rules=policy.getJSONArray("rules")
        assertTrue(rules.length()>0)
        assertTrue((0 until rules.length()).all {rules.getJSONObject(it).getJSONObject("match").length()>0})
    }
    @Test fun generatedCarrierDnsNoLongerRequiresInspectingEveryTcpConnection() {
        val entry=ServerImport.Server("owned-csqtt","Fixture CSQTT","csqtt","example.test",443,null,null,null,JSONObject().put("type","csqtt"))
        val source=ServerImport.build(listOf(entry),entry.id,false,60,30,"Системный")
        DnsConfig.applyGenerated(source,DnsConfig.defaults())
        val tag=source.getJSONObject("mcrf_carrier").getString("outbound_tag")
        val custom=JSONObject(source.toString())
        custom.getJSONObject("route").getJSONArray("rules").getJSONObject(1).put("port",5353)
        val before=custom.toString()
        BsNativeProfilePreparation.scopeGeneratedCarrierDns(custom,tag)
        assertEquals(before,custom.toString())
        BsNativeProfilePreparation.scopeGeneratedCarrierDns(source,tag)
        val config=ProfileConfig.build(source.toString(),true,10808)
        val captured=BsNativeRoutingSnapshot.capture(RouteRules.apply(config,RouteRules.defaults(),true,nativeBs=true),tag,emptyMap())
        val controls=captured.controls()
        val dns=(0 until controls.length()).map {controls.getJSONObject(it).getJSONObject("operation")}.single {it.getString("action")=="hijack-dns"}
        assertEquals(53,dns.getInt("port"))
        assertFalse(dns.has("protocol"))
    }
    @Test fun private_worker_bundle_roundtrip_retains_policy_and_refuses_malformed_optional_fields() {
        val captured=BsNativeRoutingSnapshot.capture(config(),"chosen",emptyMap());val bundle=captured.bundle()
        val copied=BsNativeRoutingSnapshot.fromBundle(bundle);assertEquals(bundle.toString(),copied.bundle().toString())
        bundle.getJSONObject("policy").put("defaultTarget","block");assertEquals("proxy",copied.policy().getString("defaultTarget"))
        for((key,value)in listOf("listeners" to JSONObject(),"certificates" to JSONArray(),"dns" to JSONArray(),"egressBindings" to JSONObject(),"extra" to true)) {
            val bad=captured.bundle().put(key,value);assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.fromBundle(bad)}
        }
    }
    @Test fun explicit_routes_keep_independent_worker_and_dns_bindings() {
        val source=config()
        val first=BsNativeRoutingSnapshot.Egress("a".repeat(64),10820,"fixture-user-0001","fixture-password-0001")
        val second=BsNativeRoutingSnapshot.Egress("b".repeat(64),10821,"fixture-user-0002","fixture-password-0002")
        val workers=listOf(first,second)
        for(worker in workers)source.getJSONArray("outbounds").put(JSONObject().put("tag",worker.tag).put("type","socks"))
        source.getJSONObject("route").put("rules",JSONArray()
            .put(JSONObject().put("package_name",JSONArray().put("org.example.one")).put("outbound",first.tag))
            .put(JSONObject().put("domain_suffix","owned.invalid").put("outbound",second.tag)))
        source.put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("type","tcp").put("tag","named")
            .put("server","192.0.2.53").put("detour",second.tag))).put("final","named"))
        val captured=BsNativeRoutingSnapshot.capture(source,"chosen",emptyMap(),egresses=workers)
        assertEquals(2,captured.policy().getInt("schema"))
        assertEquals(first.tag,captured.policy().getJSONArray("rules").getJSONObject(0).getString("target"))
        assertEquals(second.tag,captured.dnsDialers().getString(second.tag))
        val bundle=captured.bundle()
        assertEquals(first.tag,bundle.getJSONArray("egressBindings").getJSONObject(0).getString("tag"))
        assertEquals(10821,bundle.getJSONArray("egressBindings").getJSONObject(1).getInt("port"))
        assertTrue(RoutingDefaultsEdits.equivalent(bundle,BsNativeRoutingSnapshot.fromBundle(bundle).bundle()))
        assertFalse(first.toString().contains("fixture"))
        assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.capture(source,"chosen",emptyMap(),egresses=listOf(first))}
        val neutral=BsNativeRoutingSnapshot.capture(source,"chosen",emptyMap(),egresses=workers,mainEgressId=first.serverId)
        assertEquals(first.tag,neutral.policy().getString("defaultTarget"))
        assertEquals(first.tag,neutral.dnsDialers().getString("chosen"))
        assertThrows(IllegalArgumentException::class.java){
            BsNativeRoutingSnapshot.capture(source,"chosen",emptyMap(),egresses=workers,mainEgressId="c".repeat(64))
        }
    }
    @Test fun trust_store_and_active_root_services_cannot_disappear_from_a_snapshot() {
        val source=config().put("certificate",JSONObject().put("store","system"))
        assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.capture(source,"chosen",emptyMap())}
        val roots=BsNativeCertificates.encode("system",listOf(byteArrayOf(1,2,3))){false}
        val snapshot=BsNativeRoutingSnapshot.capture(source,"chosen",emptyMap(),roots)
        roots.getJSONArray("roots").put(0,"changed");assertEquals("AQID",snapshot.bundle().getJSONObject("certificates").getJSONArray("roots").getString(0))
        snapshot.certificates()!!.put("store","mozilla");assertEquals("system",snapshot.certificates()!!.getString("store"))
        for((key,value)in listOf("ntp" to JSONObject().put("enabled",true),"experimental" to JSONObject().put("clash_api",JSONObject()),"services" to JSONArray().put(JSONObject().put("type","api")),"endpoints" to JSONArray().put(JSONObject().put("type","tailscale")))) {
            val bad=config().put(key,value);assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.capture(bad,"chosen",emptyMap())}
        }
    }
    @Test fun generated_loopback_binding_is_admitted_only_for_its_exact_private_route() {
        fun source()=ProfileConfig.build("""{"outbounds":[{"type":"socks","tag":"chosen","server":"127.0.0.1","server_port":12345}],"route":{"final":"chosen","rules":[]}}""",true,10808)
        val captured=BsNativeRoutingSnapshot.capture(source(),"chosen",emptyMap())
        assertEquals("direct",captured.policy().getJSONArray("rules").getJSONObject(0).getString("target"))
        assertEquals(1,captured.listeners().length())
        val ordered=source();val first=ordered.getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        ordered.getJSONObject("route").put("rules",JSONArray().put(JSONObject().put("action","sniff")).put(first))
        val afterSniff=BsNativeRoutingSnapshot.capture(ordered,"chosen",emptyMap());assertEquals(1,afterSniff.controls().length());assertEquals("direct",afterSniff.policy().getJSONArray("rules").getJSONObject(0).getString("target"))

        repeat(5){index->val source=source();val route=source.getJSONObject("route");val tag=source.getJSONArray("outbounds").getJSONObject(1).getString("tag")
            when(index){
                0->route.put("final",tag)
                1->route.getJSONArray("rules").getJSONObject(0).put("ip_cidr",JSONArray().put("0.0.0.0/0"))
                2->route.getJSONArray("rules").put(JSONObject().put("outbound",tag))
                3->source.put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("type","udp").put("tag","d").put("server","192.0.2.53").put("detour",tag))))
                else->source.getJSONArray("outbounds").getJSONObject(1).put("inet4_bind_address","0.0.0.0")
            }
            assertThrows(Exception::class.java){BsNativeRoutingSnapshot.capture(source,"chosen",emptyMap())}
        }
    }
    @Test fun local_proxy_and_other_listeners_are_not_silently_dropped() {
        val source=config();val listener=JSONObject("""{"type":"mixed","tag":"local-proxy","listen":"127.0.0.1","listen_port":10808,"users":[{"username":"owned","password":"owned-fixture"}]}""")
        source.getJSONArray("inbounds").put(listener)
        val snapshot=BsNativeRoutingSnapshot.capture(source,"chosen",emptyMap());listener.put("listen_port",12345)
        assertEquals(10808,snapshot.listeners().getJSONObject(0).getInt("listen_port"))
        val bundle=snapshot.bundle();assertEquals("mixed",bundle.getJSONArray("listeners").getJSONObject(0).getString("type"))
        bundle.getJSONArray("listeners").getJSONObject(0).put("listen_port",12345)
        assertEquals(10808,snapshot.bundle().getJSONArray("listeners").getJSONObject(0).getInt("listen_port"))
        assertFalse(BsNativeRoutingSnapshot.capture(config(),"chosen",emptyMap()).bundle().has("listeners"))
    }
    @Test fun malformed_source_snapshots_are_rejected_before_parsing_or_native_start() {
        val config=config();config.getJSONObject("route").put("rule_set",JSONArray("""[{"type":"local","tag":"owned","format":"source","path":"/owned/geo.json"}]"""))
        for(bytes in listOf(byteArrayOf(0xc3.toByte(),0x28),("[".repeat(65)+"]".repeat(65)).toByteArray(),"""{"version":6,"rules":[]}""".toByteArray()))
            assertThrows(Exception::class.java){BsNativeRoutingSnapshot.captureFiles(config,"chosen",mapOf("owned" to bytes))}
    }
    @Test fun source_and_binary_files_belong_to_this_exact_document() {
        val config=config();config.getJSONObject("route").put("rule_set",JSONArray("""[{"type":"local","tag":"owned","format":"source","path":"/owned/geo.json"}]"""))
        for(version in 1..5){val source="""{"version":$version,"rules":[{"domain":"owned.invalid"}]}""".toByteArray()
            val captured=BsNativeRoutingSnapshot.captureFiles(config,"chosen",mapOf("owned" to source))
            source.fill(0);assertEquals(version,captured.policy().getJSONObject("ruleSets").getJSONObject("owned").getInt("version"))}
        val rows=JSONArray(javaClass.getResourceAsStream("/bs-srs-reference.json")!!.bufferedReader().use {it.readText()})
        config.getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).put("format","binary")
        val binary=java.util.Base64.getDecoder().decode(rows.getJSONObject(0).getString("binary"))
        val captured=BsNativeRoutingSnapshot.captureFiles(config,"chosen",mapOf("owned" to binary));binary.fill(0)
        assertEquals(rows.getJSONObject(0).getJSONObject("expected").getInt("version"),captured.policy().getJSONObject("ruleSets").getJSONObject("owned").getInt("version"))
        assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.captureFiles(config,"chosen",emptyMap())}
        assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.captureFiles(config,"chosen",mapOf("foreign" to byteArrayOf(1)))}
        config.getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).put("type","remote")
        assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.captureFiles(config,"chosen",mapOf("owned" to byteArrayOf(1)))}
    }
    private fun config()=JSONObject("""{"route":{"final":"chosen","rules":[
      {"action":"sniff"},{"protocol":"dns","action":"hijack-dns"},
      {"domain_suffix":["example.org"],"action":"route","outbound":"direct"},
      {"action":"resolve","strategy":"ipv4_only"},
      {"ip_cidr":["192.0.2.0/24"],"action":"reject"}]},
      "outbounds":[{"type":"socks","tag":"chosen"},{"type":"direct","tag":"direct"}],
      "dns":{"final":"private-dns","servers":[{"type":"https","tag":"private-dns","server":"192.0.2.53","path":"/dns-query"}]},
      "inbounds":[{"type":"tun","exclude_package":["org.example.excluded"]}]}""")
    @Test fun retains_rule_order_dns_and_control_positions_without_changing_the_source() {
        val config=config();config.getJSONObject("route").put("default_domain_resolver","private-dns")
        val before=config.toString();val captured=BsNativeRoutingSnapshot.capture(config,"chosen",emptyMap())
        val rules=captured.policy().getJSONArray("rules");assertEquals(2,rules.length())
        assertEquals("direct",rules.getJSONObject(0).getString("target"));assertEquals("block",rules.getJSONObject(1).getString("target"))
        val operations=captured.controls();assertEquals(3,operations.length());assertEquals(1,operations.getJSONObject(2).getInt("beforeRule"))
        assertEquals(config.getJSONObject("dns").toString(),captured.dns()!!.toString())
        assertEquals("private-dns",captured.routeOptions().getString("default_domain_resolver"))
        assertEquals("org.example.excluded",captured.excludedPackages().getString(0));assertEquals(before,config.toString())
        captured.policy().put("defaultTarget","direct");captured.dns()!!.put("final","changed")
        assertEquals("proxy",captured.policy().getString("defaultTarget"));assertEquals("private-dns",captured.dns()!!.getString("final"))
    }
    @Test fun private_sets_are_captured_and_missing_or_foreign_sets_never_use_a_global_fallback() {
        val config=config();config.getJSONObject("route").put("rule_set",JSONArray("""[{"type":"local","tag":"private-set","path":"/owned/profile/geo.json"}]"""))
        val set=JSONObject("""{"version":3,"rules":[{"domain_suffix":["private.example"]}]}""")
        assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.capture(config,"chosen",emptyMap())}
        val custom=this.config();custom.getJSONArray("outbounds").getJSONObject(1).put("bind_interface","wlan0")
        assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.capture(custom,"chosen",emptyMap())}
        val snapshot=BsNativeRoutingSnapshot.capture(config,"chosen",mapOf("private-set" to set));set.put("version",1)
        assertEquals(3,snapshot.policy().getJSONObject("ruleSets").getJSONObject("private-set").getInt("version"))
        assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.capture(config,"chosen",mapOf("private-set" to set,"foreign" to set))}
    }
    @Test fun other_servers_actions_and_special_rejects_are_not_silently_replaced() {
        for(rule in listOf("""{"action":"route","outbound":"another-server"}""","""{"action":"unsupported"}""","""{"action":"reject","reject_method":"drop"}""")) {
            val config=config();config.getJSONObject("route").put("rules",JSONArray().put(JSONObject(rule)))
            assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.capture(config,"chosen",emptyMap())}
        }
        val config=config();config.getJSONObject("route").put("unknown_option",true)
        assertThrows(IllegalArgumentException::class.java){BsNativeRoutingSnapshot.capture(config,"chosen",emptyMap())}
    }
    @Test fun captures_complete_tun_and_dns_dialer_documents_as_independent_copies() {
        val config=config();val tun=config.getJSONArray("inbounds").getJSONObject(0)
        tun.put("include_package",JSONArray().put("org.example.only"))
            .put("route_exclude_address",JSONArray().put("192.0.2.0/24"))
            .put("address",JSONArray().put("10.89.0.2/30"))
        val captured=BsNativeRoutingSnapshot.capture(config,"chosen",emptyMap())
        assertEquals(tun.toString(),captured.tunInbounds().getJSONObject(0).toString())
        assertEquals("proxy",captured.dnsDialers().getString("chosen"));assertEquals("direct",captured.dnsDialers().getString("direct"))
        tun.put("include_package",JSONArray());captured.tunInbounds().getJSONObject(0).put("address",JSONArray())
        captured.dnsDialers().put("chosen","direct")
        assertEquals("org.example.only",captured.tunInbounds().getJSONObject(0).getJSONArray("include_package").getString(0))
        assertEquals("10.89.0.2/30",captured.tunInbounds().getJSONObject(0).getJSONArray("address").getString(0))
        assertEquals("proxy",captured.dnsDialers().getString("chosen"))
        val bundle=captured.bundle();assertEquals(1,bundle.getInt("schema"))
        assertEquals("org.example.only",bundle.getJSONArray("tunInbounds").getJSONObject(0).getJSONArray("include_package").getString(0))
        bundle.getJSONObject("dns").put("final","mutated");bundle.getJSONObject("policy").put("defaultTarget","block")
        assertEquals("private-dns",captured.bundle().getJSONObject("dns").getString("final"));assertEquals("proxy",captured.bundle().getJSONObject("policy").getString("defaultTarget"))
    }
}
