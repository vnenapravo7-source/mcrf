package org.mcrf.client;

import org.json.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class RouteRulesTest {
    @Test public void nativeHostVectorsUseActualCompilerAndKeepBothAddressFamilies() throws Exception {
        for(boolean inverse:new boolean[]{false,true}) {
            JSONObject config=new JSONObject().put("inbounds",new JSONArray()).put("outbounds",new JSONArray()
                .put(new JSONObject().put("type","direct").put("tag","direct"))
                .put(new JSONObject().put("type","http").put("tag","fixture-via").put("server","127.0.0.1").put("server_port",1)))
                .put("route",new JSONObject().put("rules",new JSONArray()).put("final","fixture-via"));
            JSONObject state=RouteRules.defaults().put("defaultTarget",inverse?"direct":"proxy");
            state.getJSONArray("rules").put(rule("ru.fixture.test\n127.0.0.2/32\n::1/128","addresses",inverse?"proxy":"direct").put("invert",inverse));
            JSONObject result=RouteRules.apply(config,state,true);
            JSONArray compiled=result.getJSONObject("route").getJSONArray("rules");
            assertEquals("sniff",compiled.getJSONObject(0).getString("action"));
            assertEquals("hijack-dns",compiled.getJSONObject(1).getString("action"));
            assertEquals("resolve",compiled.getJSONObject(2).getString("action"));
            assertEquals(inverse?"direct":"fixture-via",result.getJSONObject("route").getString("final"));
            // Owned fixture only, no profile or account values. The host native
            // runner consumes this exact archived compiler output, not a copy of its logic.
            System.out.println("MCRF_ROUTE_VECTOR:"+(inverse?"inverted":"ordinary")+":"+result.toString());
        }
        JSONObject config=new JSONObject().put("inbounds",new JSONArray()).put("outbounds",new JSONArray()
            .put(new JSONObject().put("type","direct").put("tag","direct"))
            .put(new JSONObject().put("type","http").put("tag","fixture-via").put("server","127.0.0.1").put("server_port",1)))
            .put("route",new JSONObject().put("rules",new JSONArray()).put("final","fixture-via"));
        JSONObject state=RouteRules.defaults();
        state.getJSONArray("rules").put(rule("ru.fixture.test","addresses","direct"))
            .put(rule("","addresses","direct").put("nativeMatch",new JSONObject().put("rule_set",new JSONArray().put("mcrf-geo-0"))));
        state.put("geoData",GeoDataConfig.INSTANCE.defaults().put("selectors",new JSONArray().put(new JSONObject().put("kind","geoip").put("code","ru").put("target","direct").put("bound",true))));
        JSONObject result=RouteRules.apply(config,state,true,Collections.singletonMap("geo-0","/fixture/owned-geoip.json"));
        System.out.println("MCRF_ROUTE_VECTOR:geoip:"+result.toString());
        String preset="0123456789abcdef";
        state=RouteRules.defaults();state.getJSONArray("rules").put(rule("ru.fixture.test","addresses","direct"))
            .put(rule("","addresses","direct").put("presetIds",new JSONArray().put(preset)));
        result=RouteRules.apply(config,state,true,Collections.singletonMap(preset,"/fixture/owned-preset.srs"),Collections.singleton(PresetRules.INSTANCE.tag(preset)));
        System.out.println("MCRF_ROUTE_VECTOR:preset:"+result.toString());
    }
    @Test public void ipTextIgnoredByAppsOnlyModeDoesNotInjectDnsResolve() throws Exception {
        JSONObject state=RouteRules.defaults();state.getJSONArray("rules").put(rule("192.0.2.0/24\napp:org.example.client","apps","direct"));
        JSONArray rules=RouteRules.apply(base(),state,true).getJSONObject("route").getJSONArray("rules");
        for(int i=0;i<rules.length();i++)assertNotEquals("resolve",rules.getJSONObject(i).optString("action"));
    }
    @Test public void destinationIpResolvePreservesEarlierDomainRuleAndRunsOnlyOnce() throws Exception {
        JSONObject state=RouteRules.defaults();state.getJSONArray("rules").put(rule("DOMAIN,first.example","addresses","proxy"))
            .put(rule("192.0.2.0/24","addresses","direct")).put(rule("198.51.100.0/24","addresses","block"));
        JSONArray rules=RouteRules.apply(base(),state,true).getJSONObject("route").getJSONArray("rules");
        int domain=-1,resolve=-1,ip=-1,count=0;
        for(int i=0;i<rules.length();i++){JSONObject item=rules.getJSONObject(i);if(item.optJSONArray("domain")!=null&&item.getJSONArray("domain").optString(0).equals("first.example"))domain=i;if(item.optString("action").equals("resolve")){resolve=i;count++;}if(item.optJSONArray("ip_cidr")!=null&&item.getJSONArray("ip_cidr").optString(0).equals("192.0.2.0/24"))ip=i;}
        assertEquals(1,count);assertTrue(domain>=0&&resolve>domain&&ip>resolve);
    }
    @Test public void resolvePrecedesFirstIpRuleAndCountryInversionWithoutChangingFinal() throws Exception {
        JSONObject state=RouteRules.defaults().put("defaultTarget","direct");
        state.getJSONArray("rules").put(RouteRules.outsideRussia("ru\n192.0.2.0/24"));
        JSONObject result=RouteRules.apply(base(),state,true);
        JSONArray rules=result.getJSONObject("route").getJSONArray("rules");
        int resolve=-1,invert=-1,count=0;
        for(int i=0;i<rules.length();i++){JSONObject item=rules.getJSONObject(i);if(item.optString("action").equals("resolve")){resolve=i;count++;}if(item.optBoolean("invert"))invert=i;}
        assertEquals(1,count);assertTrue(resolve>=0&&invert>resolve);
        assertEquals("direct",result.getJSONObject("route").getString("final"));
    }
    private static JSONObject rule(String text,String mode,String target) throws Exception { return new JSONObject().put("id","fixture").put("name","Fixture").put("text",text).put("mode",mode).put("target",target).put("enabled",true); }
    static JSONObject base() throws Exception { return ProfileConfig.build("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],\"route\":{\"final\":\"direct\"}}",true,10808); }
    @Test public void sharedCorpus() throws Exception {
        JSONArray cases; try(java.io.InputStream input=getClass().getResourceAsStream("/route-cases.json")) { cases=new JSONArray(new String(input.readAllBytes(),StandardCharsets.UTF_8)); }
        for(int i=0;i<cases.length();i++) { JSONObject data=cases.getJSONObject(i); Map<String,List<String>> groups=RouteRules.parse(data.getString("text"));
            assertEquals(data.getInt("domains"),groups.get("domain").size()); assertEquals(data.getInt("suffixes"),groups.get("domain_suffix").size());
            assertEquals(data.getInt("ips"),groups.get("ip_cidr").size()); assertEquals(data.getInt("apps"),groups.get("package_name").size());
            assertEquals(data.getInt("matches"),RouteRules.matches(rule(data.getString("text"),data.getString("mode"),"proxy")).length()); }
    }
    @Test public void nestedRulesHaveNoActions() throws Exception {
        JSONObject outer=RouteRules.matches(rule("example.com\napp:org.example.client","in-apps","proxy")).getJSONObject(0);
        assertEquals("and",outer.getString("mode")); assertFalse(outer.getJSONArray("rules").getJSONObject(0).has("action"));
        assertTrue(RouteRules.matches(rule("example.com\napp:org.example.client","except-apps","proxy")).getJSONObject(0).getJSONArray("rules").getJSONObject(1).getBoolean("invert"));
    }
    @Test public void orderedBlockAndDirect() throws Exception {
        JSONObject state=RouteRules.defaults(); state.getJSONArray("rules").put(rule("blocked.example","addresses","block")).put(rule("allowed.example","addresses","direct").put("id","second"));
        JSONObject compiled=RouteRules.apply(base(),state,true); JSONArray rules=compiled.getJSONObject("route").getJSONArray("rules");
        assertEquals("mcrf-loopback",rules.getJSONObject(0).getString("outbound")); assertEquals("sniff",rules.getJSONObject(1).getString("action"));
        assertEquals("reject",rules.getJSONObject(3).getString("action")); assertEquals("route",rules.getJSONObject(4).getString("action")); assertEquals("direct",rules.getJSONObject(4).getString("outbound"));
        assertEquals(1,base().getJSONObject("route").getJSONArray("rules").length());
    }
    @Test public void perRuleDnsAndGlobalExclusion() throws Exception {
        JSONObject config=base(); DnsConfig.applyGenerated(config,DnsConfig.defaults()); JSONObject state=RouteRules.defaults(); state.getJSONArray("excludedApps").put("org.example.excluded");
        JSONObject dns=new JSONObject().put("provider","Google").put("protocol","DoH").put("custom",""); state.getJSONArray("rules").put(rule("example.com","addresses","proxy").put("dns",dns));
        JSONObject result=RouteRules.apply(config,state,true); assertEquals("org.example.excluded",result.getJSONArray("inbounds").getJSONObject(0).getJSONArray("exclude_package").getString(0));
        assertEquals("dns-list-fixture",result.getJSONObject("dns").getJSONArray("rules").getJSONObject(0).getString("server"));
        assertFalse(result.getJSONObject("dns").getJSONArray("servers").getJSONObject(2).has("detour"));
    }
    @Test public void disabledRulesDoNotExecute() throws Exception { JSONObject state=RouteRules.defaults(); state.getJSONArray("rules").put(rule("example.com","addresses","block").put("enabled",false)); assertEquals(3,RouteRules.apply(base(),state,true).getJSONObject("route").getJSONArray("rules").length()); }
    @Test(expected=IllegalArgumentException.class) public void malformedNetworkRejected() throws Exception { RouteRules.parse("10.0.0.1/33"); }
    @Test(expected=IllegalArgumentException.class) public void emptyRuleRejected() throws Exception { RouteRules.matches(rule("# none","any","proxy")); }
    @Test(expected=IllegalArgumentException.class) public void desktopExecutableRejected() throws Exception { RouteRules.parse("app:game.exe --argument"); }
    @Test(expected=IllegalArgumentException.class) public void appRulesRequireVpn() throws Exception { JSONObject state=RouteRules.defaults(); state.getJSONArray("rules").put(rule("app:org.example.client","apps","proxy")); RouteRules.apply(base(),state,false); }
    @Test(expected=IllegalArgumentException.class) public void appDnsNeverBroadened() throws Exception { RouteRules.matches(rule("example.com\napp:org.example.client","in-apps","proxy").put("dns",DnsConfig.defaults())); }
    @Test public void providerTransportMatrix() throws Exception { for(String provider:DnsConfig.PROVIDERS) for(String protocol:DnsConfig.protocols(provider)) {
        if(provider.equals("Свой")) continue; JSONObject server=DnsConfig.build(new JSONObject().put("provider",provider).put("protocol",protocol),"chosen","bootstrap"); assertFalse(server.getString("type").isEmpty()); } }
    @Test public void customDnsPreservesPathAndPort() throws Exception { JSONObject server=DnsConfig.build(new JSONObject().put("provider","Свой").put("protocol","DoH").put("custom","https://dns.example:8443/custom-query"),"chosen","bootstrap"); assertEquals(8443,server.getInt("server_port")); assertEquals("/custom-query",server.getString("path")); assertEquals("bootstrap",server.getString("domain_resolver")); }
    @Test(expected=IllegalArgumentException.class) public void customDnsWrongProtocolRejected() throws Exception { DnsConfig.build(new JSONObject().put("provider","Свой").put("protocol","DoT").put("custom","https://secret@dns.example"),"chosen","bootstrap"); }
    @Test public void invertAddressesOnly() throws Exception { assertTrue(RouteRules.matches(rule("example.com","addresses","proxy").put("invert",true)).getJSONObject(0).getBoolean("invert")); }
}
