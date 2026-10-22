package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerBootstrapDnsTest {
    private fun node(type:String,host:String)=JSONObject().put("type",type).put("server",host)
    @Test fun domainServerUsesBootstrapButDirectDestinationAndDnsRoutingStayUnchanged() {
        val proxy=node("vless","vpn.example.test").put("tag","proxy")
        val direct=node("direct","destination.example.test").put("tag","direct")
        val dns=JSONObject().put("final","remote").put("rules",JSONArray().put(JSONObject().put("domain_suffix",JSONArray().put("ru")).put("server","direct-dns")))
        val config=JSONObject().put("outbounds",JSONArray().put(proxy).put(direct)).put("dns",dns)
            .put("route",JSONObject().put("default_domain_resolver","direct-dns"))
        val before=dns.toString()
        ServerBootstrapDns.apply(config,"bootstrap","prefer_ipv6")
        assertEquals("bootstrap",proxy.getJSONObject("domain_resolver").getString("server"))
        assertEquals("prefer_ipv6",proxy.getJSONObject("domain_resolver").getString("strategy"))
        assertFalse(direct.has("domain_resolver"))
        assertEquals("direct-dns",config.getJSONObject("route").getString("default_domain_resolver"))
        assertEquals(before,dns.toString())
    }
    @Test fun explicitResolverIpv4AndIpv6LiteralsArePreserved() {
        val explicit=node("trojan","vpn.example.test").put("domain_resolver","profile-dns")
        val v4=node("socks","192.0.2.1");val v6=node("vless","2001:db8::1")
        val config=JSONObject().put("outbounds",JSONArray().put(explicit).put(v4).put(v6))
        val before=config.toString();ServerBootstrapDns.apply(config,"bootstrap","")
        assertEquals(before,config.toString())
    }
    @Test fun wireguardPeerDomainOnlyAndRepeatedPreparationIsIdempotent() {
        val domain=JSONObject().put("address","wg.example.test");val literal=JSONObject().put("address","2001:db8::2")
        val endpoint=JSONObject().put("type","wireguard").put("peers",JSONArray().put(domain).put(literal))
        val config=JSONObject().put("endpoints",JSONArray().put(endpoint))
        ServerBootstrapDns.apply(config,"bootstrap","")
        assertEquals("bootstrap",endpoint.getJSONObject("domain_resolver").getString("server"))
        assertFalse(endpoint.getJSONObject("domain_resolver").has("strategy"))
        assertFalse(domain.has("domain_resolver"));assertFalse(literal.has("domain_resolver"))
        val once=config.toString();ServerBootstrapDns.apply(config,"bootstrap","ipv4_only");assertEquals(once,config.toString())
    }
    @Test fun literalEndpointAndExplicitEndpointResolverRemainExact() {
        val literal=JSONObject().put("type","wireguard").put("peers",JSONArray().put(JSONObject().put("address","2001:db8::3")))
        val explicit=JSONObject().put("type","wireguard").put("domain_resolver","profile-dns")
            .put("peers",JSONArray().put(JSONObject().put("address","wg.example.test")))
        val config=JSONObject().put("endpoints",JSONArray().put(literal).put(explicit))
        val before=config.toString();ServerBootstrapDns.apply(config,"bootstrap","ipv6_only");assertEquals(before,config.toString())
    }
    @Test fun generatedMainPlaceholderUsesSelectedServerDnsNotTheOldSystemPlaceholder(){
        val entry=ServerImport.read("socks://fixture:owned@server.example.test:1080").single()
        val source=ServerImport.build(listOf(entry),entry.id,false,60,50,"Cloudflare")
        DnsConfig.applyGenerated(source,DnsConfig.defaults())
        val state=RouteRules.defaults().put("advancedDns",AdvancedDnsConfig.defaults())
        state.getJSONObject("advancedDns").getJSONObject("local").put("provider","Google").put("protocol","DoT")
        val input=source.toString()
        val result=RouteRules.apply(ProfileConfig.build(source.toString(),true,10808),state,true,routeServers=listOf(entry))
        val node=result.getJSONArray("outbounds").let {a->(0 until a.length()).map {a.getJSONObject(it)}.single {it.optString("tag")==ServerImport.tag(entry)}}
        assertEquals("mcrf-dns-local",node.getJSONObject("domain_resolver").getString("server"))
        val local=result.getJSONObject("dns").getJSONArray("servers").let {a->(0 until a.length()).map {a.getJSONObject(it)}.single {it.optString("tag")=="mcrf-dns-local"}}
        assertEquals("tls",local.getString("type"));assertEquals("8.8.8.8",local.getString("server"))
        assertEquals(input,source.toString());assertFalse(entry.outbound!!.has("domain_resolver"))
    }
    @Test fun explicitResolverEvenWithGeneratedLookingValueIsNeverOverwritten(){
        val imported=ServerImport.read("socks://fixture:owned@server.example.test:1080").single()
        val explicit=JSONObject().put("server","bootstrap").put("strategy","prefer_ipv4")
        val entry=ServerImport.Server(imported.original,imported.name,imported.protocol,imported.host,imported.port,
            JSONObject(imported.outbound!!.toString()).put("domain_resolver",explicit),null,null)
        val built=ServerImport.build(listOf(entry),entry.id,false,60,50,"Cloudflare")
        assertFalse(ServerBootstrapDns.implicitTags(listOf(entry)).contains(ServerImport.tag(entry)))
        ServerBootstrapDns.apply(built,"selected-local","prefer_ipv6",ServerBootstrapDns.implicitTags(listOf(entry)))
        assertEquals(explicit.toString(),built.getJSONArray("outbounds").getJSONObject(1).getJSONObject("domain_resolver").toString())
        val different=JSONObject(entry.outbound.toString()).put("domain_resolver","profile-resolver")
        val changed=ServerImport.Server(entry.original,entry.name,entry.protocol,entry.host,entry.port,different,null,null)
        assertEquals("profile-resolver",ServerImport.build(listOf(changed),changed.id,false,60,50,"Cloudflare").getJSONArray("outbounds").getJSONObject(1).getString("domain_resolver"))
    }
}
