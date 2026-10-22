package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Queued source cases, not proof of native protocol/UID/DNS/Android traffic. */
class NativeRouteDispatchPlanTest {
    private fun server(name:String)=ServerImport.read("socks://user:password@$name.example.test:1080#$name").single()
    private fun plan(main:ServerImport.Server,vararg other:ServerImport.Server):NativeRouteDispatchPlan {
        val rules=JSONArray(other.mapIndexed {index,entry->JSONObject().put("id","$index").put("name","$index")
            .put("text","$index.example.test").put("mode","addresses").put("target","proxy").put("serverId",entry.id)})
        return NativeRouteDispatchPlan.forSteer(main,RouteServerPlan(RouteRules.defaults().put("rules",rules),other.toList()))
    }
    private fun bridge()=JSONObject().put("type","socks").put("version","5").put("server","127.0.0.1")
        .put("server_port",19001).put("username","a".repeat(32)).put("password","b".repeat(64))
    private fun source(main:ServerImport.Server,other:ServerImport.Server):JSONObject=JSONObject()
        .put("outbounds",JSONArray().put(JSONObject(main.outbound!!.toString()).put("tag",ServerImport.tag(main)))
            .put(JSONObject(other.outbound!!.toString()).put("tag",RouteServerPlan.tag(other.id))))
        .put("route",JSONObject().put("final",ServerImport.tag(main)).put("rules",JSONArray().put(JSONObject().put("outbound",RouteServerPlan.tag(other.id)))))
        .put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("detour",RouteServerPlan.tag(other.id)))))
    @Test fun serverPoolAndNestedSelectorResolveInDispatcherAndKeepOnlyExplicitMembers() {
        val main=server("main");val first=server("first");val second=server("second")
        val rule=JSONObject().put("id","pool").put("target","proxy").put("mode","addresses").put("text","pool.example.test")
            .put("serverIds",JSONArray().put(first.id).put(second.id))
        val extra=RouteServerPlan(RouteRules.defaults().put("rules",JSONArray().put(rule)),listOf(first,second))
        val pool=RouteServerPlan.outbound(rule,"unused-global")
        val template=JSONObject().put("outbounds",JSONArray().put(JSONObject(main.outbound!!.toString()).put("tag",ServerImport.tag(main))))
            .put("route",JSONObject().put("final",ServerImport.tag(main)).put("rules",JSONArray().put(JSONObject()
                .put("domain",JSONArray().put("pool.example.test")).put("outbound","nested-pool").put("action","route"))))
            .put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("tag","pool-dns").put("detour",pool))))
        val config=extra.append(template)
        config.getJSONArray("outbounds").put(JSONObject().put("type","selector").put("tag","nested-pool").put("outbounds",JSONArray().put(pool)))
        val plan=NativeRouteDispatchPlan.forSteer(main,extra)
        val output=plan.replace(config,plan.bindings.associate {it.id to bridge()})
        val rules=output.getJSONObject("route").getJSONArray("rules")
        assertEquals("resolve",rules.getJSONObject(0).getString("action"))
        assertEquals("pool.example.test",rules.getJSONObject(0).getJSONArray("domain").getString(0))
        assertEquals("nested-pool",rules.getJSONObject(1).getString("outbound"))
        assertEquals(config.getJSONObject("dns").toString(),output.getJSONObject("dns").toString())
        val groups=output.getJSONArray("outbounds")
        val node=(0 until groups.length()).map(groups::getJSONObject).single {it.optString("tag")==pool}
        assertEquals(setOf(RouteServerPlan.tag(first.id),RouteServerPlan.tag(second.id)),(0 until node.getJSONArray("outbounds").length()).map {node.getJSONArray("outbounds").getString(it)}.toSet())
    }
    @Test fun preservesDispatcherRoutesAndDnsWhileUsingActualSelectedWorkers(){
        val main=server("main");val other=server("other");val plan=plan(main,other)
        val input=source(main,other);val before=input.toString()
        val output=plan.replace(input,plan.bindings.associate {it.id to bridge()})
        assertEquals(2,plan.bindings.size)
        val outputRoute=output.getJSONObject("route")
        assertEquals(input.getJSONObject("route").getString("final"),outputRoute.getString("final"))
        assertEquals("resolve",outputRoute.getJSONArray("rules").getJSONObject(0).getString("action"))
        assertEquals(input.getJSONObject("route").getJSONArray("rules").getJSONObject(0).toString(),outputRoute.getJSONArray("rules").getJSONObject(1).toString())
        assertEquals("resolve",outputRoute.getJSONArray("rules").getJSONObject(2).getString("action"))
        assertEquals(input.getJSONObject("dns").toString(),output.getJSONObject("dns").toString())
        assertEquals(before,input.toString());assertEquals("127.0.0.1",output.getJSONArray("outbounds").getJSONObject(1).getString("server"))
        assertEquals(NativeRouteDispatchPlan.TYPE,output.getJSONArray("outbounds").getJSONObject(1).getString("type"))
    }
    @Test fun sameSavedServerHasOneWorkerButKeepsBothTags(){
        val main=server("shared");val plan=plan(main,main)
        assertEquals(1,plan.bindings.size);assertEquals(2,plan.bindings.single().tags.size)
        plan.replace(source(main,main),mapOf(main.id to bridge()))
    }
    @Test fun legacyVmessSecureDefaultIsNotAProfileChangeButInsecureIs(){
        val fields=JSONObject().put("v","2").put("add","owned.example.test").put("port",443)
            .put("id","00000000-0000-4000-8000-000000000001").put("aid",0).put("scy","auto")
            .put("tls","tls").put("sni","owned.example.test").put("net","tcp")
        val main=ServerImport.read("vmess://"+java.util.Base64.getEncoder().encodeToString(fields.toString().toByteArray(Charsets.UTF_8))).single()
        val other=server("other");val plan=plan(main,other)
        assertFalse(main.outbound!!.getJSONObject("tls").has("insecure"))
        val prepared=CoreTuningConfig.apply(source(main,other),CoreTuningSettings())
        plan.replace(prepared,plan.bindings.associate {it.id to bridge()})
        prepared.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").put("insecure",true)
        assertThrows(IllegalArgumentException::class.java){plan.replace(prepared,plan.bindings.associate {it.id to bridge()})}
    }
    @Test fun generatedLoopbackBindDefaultsAreNotMistakenForProfileEdits(){
        val main=ServerImport.read("socks://user:password@127.0.0.1:18443#owned").single()
        val other=server("other");val plan=plan(main,other)
        val prepared=ProfileConfig.build(source(main,other).toString(),true,10808)
        plan.replace(prepared,plan.bindings.associate {it.id to bridge()})
        prepared.getJSONArray("outbounds").getJSONObject(0).put("inet4_bind_address","192.0.2.10")
        assertThrows(IllegalArgumentException::class.java){plan.replace(prepared,plan.bindings.associate {it.id to bridge()})}
    }
    @Test fun changedServerAndIncompleteBridgeSetFailClosed(){
        val main=server("main");val other=server("other");val plan=plan(main,other)
        assertThrows(IllegalArgumentException::class.java){plan.replace(source(main,other),mapOf(main.id to bridge()))}
        val changed=source(main,other);changed.getJSONArray("outbounds").getJSONObject(1).put("server_port",9999)
        assertThrows(IllegalArgumentException::class.java){plan.replace(changed,plan.bindings.associate {it.id to bridge()})}
    }
    @Test fun overEightWorkersIsExplicitNotSilentFallback(){
        assertThrows(IllegalArgumentException::class.java){plan(server("main"),*(0..7).map {server("other$it")}.toTypedArray())}
    }
    @Test fun explicitlySelectedXrayAndTlsServersKeepTheirRequiredModules(){
        val main=server("main")
        val xray=ServerImport.Server("owned-xray","xray","xray","xray.example.test",443,
            JSONObject().put("type","xray").put("outbound",JSONObject().put("protocol","socks")
                .put("settings",JSONObject().put("servers",JSONArray().put(JSONObject().put("address","xray.example.test").put("port",443))))),null,null)
        val tls=ServerImport.Server("owned-tls","tls","trojan","tls.example.test",443,
            JSONObject().put("type","trojan").put("server","tls.example.test").put("server_port",443)
                .put("password","owned-fixture-password").put("tls",JSONObject().put("enabled",true))
                .put("tls_fragment",JSONObject().put("enabled",true).put("size","10-20").put("sleep","0-1")),null,null)
        val settings=RouteRules.defaults().put("rules",JSONArray(listOf(xray,tls).mapIndexed {i,entry->
            JSONObject().put("id","$i").put("name","$i").put("mode","addresses").put("text","$i.test")
                .put("target","proxy").put("serverId",entry.id)}))
        val extra=RouteServerPlan(settings,listOf(xray,tls))
        val native=NativeRouteDispatchPlan.forSteer(main,extra)
        assertEquals(listOf(main.id),native.bindings.map {it.id})
        val input=RouteRules.apply(ServerImport.build(listOf(main),main.id,false,60,50,"Cloudflare"),settings,true,routeServers=listOf(xray,tls))
        val modules=OptionalCorePlan(input,OptionalCoreSettings())
        assertEquals(setOf("xray","tls-core"),modules.entries().map {it.request.kind}.toSet())
        val replaced=modules.replace(modules.entries().associate {it.tag to OptionalCoreConfig.bridgeOutbound(it.tag,19002,"fixture-user-long","fixture-password-long",it.request.kind)})
        val result=native.replace(replaced,mapOf(main.id to bridge()))
        val beforeRules=replaced.getJSONObject("route").getJSONArray("rules")
        val afterRules=result.getJSONObject("route").getJSONArray("rules")
        assertEquals(beforeRules.length()+1,afterRules.length()) // Only main final pre-resolve is added.
        repeat(beforeRules.length()){i->assertEquals(beforeRules.getJSONObject(i).toString(),afterRules.getJSONObject(i).toString())}
        assertEquals("resolve",afterRules.getJSONObject(afterRules.length()-1).getString("action"))
        assertEquals(input.getJSONObject("dns").toString(),result.getJSONObject("dns").toString())
        assertThrows(IllegalArgumentException::class.java){NativeRouteDispatchPlan.forSteer(tls,extra)}
    }
    @Test fun dispatcherOnlyProtocolsAreNotRewrittenAsSteerOrTheMainServer(){
        val main=server("main")
        for(uri in listOf("anytls://owned@anytls.example.test:443", "ssh://owned:fixture@ssh.example.test:22",
            "tuic://00000000-0000-4000-8000-000000000001:owned@tuic.example.test:443")){
            val other=ServerImport.read(uri).single();val native=plan(main,other)
            assertEquals(listOf(main.id),native.bindings.map {it.id})
            val input=source(main,other);val before=input.getJSONArray("outbounds").getJSONObject(1).toString()
            val result=native.replace(input,mapOf(main.id to bridge()))
            assertEquals(before,result.getJSONArray("outbounds").getJSONObject(1).toString())
            assertEquals(input.getJSONObject("dns").toString(),result.getJSONObject("dns").toString())
            assertEquals(RouteServerPlan.tag(other.id),result.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("outbound"))
        }
    }
}
