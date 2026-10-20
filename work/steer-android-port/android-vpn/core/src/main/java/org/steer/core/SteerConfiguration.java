package org.steer.core;
import android.net.VpnService;
import android.content.pm.PackageManager;
import org.json.*;
import java.util.*;
import java.net.URI;

/** Versioned host-facing configuration. Unsupported fields fail before VPN creation. */
public final class SteerConfiguration implements AutoCloseable {
    public final String url,dnsIpv4;
    public final List<String> excludedApps,includedApps;
    public final String controlApp;
    public final List<Ipv4Routes.Prefix> direct;
    public final List<String> blockedIpv4;
    public final String dohUrl;
    public final List<String> dohBootstrap;
    public final String dotUrl,encryptedDnsHost;
    public final int dotPort;
    public final List<String> encryptedDnsBootstrap;
    public final boolean managedDns;
    public final boolean ipv6;
    public final boolean ipv4OnlyDns;
    public final SteerLocalProxy localProxy;
    public static final String TUN_IPV6="fdfe:dcba:9876::1";
    public final DomainPolicy policy;
    public final DnsRulePolicy ruleDnsPolicy;
    public final Map<String,DnsUpstreamPlan> ruleDnsUpstreams;
    public final DnsUpstreamPlan globalDnsPlan; // null only for the legacy model
    private static List<String> strings(JSONObject json,String key) throws JSONException {
        JSONArray arr=json.optJSONArray(key);List<String> out=new ArrayList<>();
        if(json.has(key)&&arr==null)throw new IllegalArgumentException("Array required: "+key);
        if(arr!=null)for(int i=0;i<arr.length();i++) {
            String value=arr.getString(i);if(value.isEmpty())throw new IllegalArgumentException("Empty value: "+key);out.add(value);
        }
        return Collections.unmodifiableList(out);
    }
    public SteerConfiguration(JSONObject json) throws JSONException {
        this(json,Collections.emptyMap());
    }
    public SteerConfiguration(JSONObject json,Map<String,SrsAddressSet> ruleSets) throws JSONException {
        this(json,ruleSets,Collections.emptyMap());
    }
    public SteerConfiguration(JSONObject json,Map<String,SrsAddressSet> ruleSets,Map<String,NativeFlowMatch> flowSets) throws JSONException {
        int version=json.optInt("version",1);
        if(version!=1&&version!=2)throw new IllegalArgumentException("Unsupported config version");
        if(ruleSets==null||flowSets==null||version==1&&(!ruleSets.isEmpty()||!flowSets.isEmpty()))throw new IllegalArgumentException("Rule-set materialization requires model2");
        Set<String> known=new HashSet<>(Arrays.asList("version","url","dns_ipv4","direct_ipv4","blocked_ipv4","excluded_apps","included_apps","doh_url","doh_bootstrap","dot_url","dot_bootstrap","routing_rules","default_action"));
        if(version==2){known.add("ipv6");known.add("dns_ipv6");known.add("local_proxy");known.add("dns_rules");known.add("dns_upstreams");known.add("owner_rules");known.add("control_app");known.add("ipv4_only_dns");}
        for(Iterator<String> it=json.keys();it.hasNext();)if(!known.contains(it.next()))throw new IllegalArgumentException("Unsupported Steer setting");
        if(json.has("ipv6")&&!(json.opt("ipv6") instanceof Boolean))throw new IllegalArgumentException("Boolean IPv6 admission required");
        if(json.has("owner_rules")&&!(json.opt("owner_rules") instanceof Boolean))throw new IllegalArgumentException("Boolean application-rule admission required");
        ipv6=version==2&&json.optBoolean("ipv6",false);
        if(json.has("ipv4_only_dns")&&!(json.opt("ipv4_only_dns") instanceof Boolean))throw new IllegalArgumentException("Boolean DNS family policy required");
        ipv4OnlyDns=version==2&&json.optBoolean("ipv4_only_dns",false);
        if(json.has("dns_ipv6")&&(!ipv6||json.has("dns_ipv4")||json.has("doh_url")||json.has("dot_url")))throw new IllegalArgumentException("One explicitly captured IPv6 DNS transport required");
        url=json.getString("url");
        if(url.startsWith("http://")||url.startsWith("https://")||url.startsWith("socks4"))
            throw new IllegalArgumentException("This proxy cannot carry VPN UDP/DNS yet");
        if(SteerCore.serverHost(url)==null)throw new IllegalArgumentException("Unsupported server profile");
        dnsIpv4=json.optString("dns_ipv4","1.1.1.1");Ipv4Routes.parse(dnsIpv4+"/32");
        dohUrl=json.has("doh_url")?json.getString("doh_url"):null;
        if(dohUrl!=null) {
            URI uri=URI.create(dohUrl);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                ||uri.getPort()==0||uri.getPort()>65535||(!ipv6&&uri.getHost().contains(":")))throw new IllegalArgumentException("HTTPS DoH URL with admitted numeric bootstrap required");
        }
        dohBootstrap=strings(json,"doh_bootstrap");if(dohBootstrap.size()>8)throw new IllegalArgumentException("Too many DoH bootstrap addresses");
        for(String address:dohBootstrap)if(DnsUpstreamPlan.numeric(address).length==16&&!ipv6)throw new IllegalArgumentException("IPv6 DNS requires capture");
        if(dohUrl==null&&!dohBootstrap.isEmpty())throw new IllegalArgumentException("DoH URL required");
        dotUrl=json.has("dot_url")?json.getString("dot_url"):null;
        if(dotUrl!=null&&dohUrl!=null)throw new IllegalArgumentException("Choose DoH OR DoT");
        URI dot=dotUrl==null?null:URI.create(dotUrl);
        if(dot!=null&&(!"tls".equals(dot.getScheme())||dot.getHost()==null||dot.getRawUserInfo()!=null||dot.getRawQuery()!=null||dot.getRawFragment()!=null
            ||dot.getPort()==0||dot.getPort()==53||dot.getPort()>65535||(!ipv6&&dot.getHost().contains(":"))
            ||(dot.getRawPath()!=null&&!dot.getRawPath().isEmpty()&&!dot.getRawPath().equals("/"))))throw new IllegalArgumentException("TLS DoT host and port required; port 53 is unsupported");
        List<String> dotBootstrap=strings(json,"dot_bootstrap");
        if(dotBootstrap.size()>8)throw new IllegalArgumentException("Too many DoT bootstrap addresses");
        for(String address:dotBootstrap)if(DnsUpstreamPlan.numeric(address).length==16&&!ipv6)throw new IllegalArgumentException("IPv6 DNS requires capture");
        if(dot==null&&!dotBootstrap.isEmpty())throw new IllegalArgumentException("DoT URL required");
        dotPort=dot==null||dot.getPort()==-1?853:dot.getPort();
        String tlsHost=dot!=null?dot.getHost():dohUrl!=null?URI.create(dohUrl).getHost():null;
        encryptedDnsHost=tlsHost==null?null:version==2?DnsUpstreamPlan.normalizedHost(tlsHost):tlsHost;
        encryptedDnsBootstrap=dot!=null?dotBootstrap:dohBootstrap;
        if(version==2){
            JSONObject global=new JSONObject();
            if(dotUrl!=null)global.put("dot_url",dotUrl).put("dot_bootstrap",new JSONArray(encryptedDnsBootstrap));
            else if(dohUrl!=null)global.put("doh_url",dohUrl).put("doh_bootstrap",new JSONArray(encryptedDnsBootstrap));
            else if(json.has("dns_ipv6"))global.put("dns_ipv6",json.getString("dns_ipv6"));
            else global.put("dns_ipv4",dnsIpv4);
            globalDnsPlan=new DnsUpstreamPlan(global);
            if(globalDnsPlan.usesIpv6()&&!ipv6)throw new IllegalArgumentException("IPv6 GLOBAL DNS requires capture");
        }else globalDnsPlan=null;
        if(json.has("routing_rules")&&json.optJSONArray("routing_rules")==null)throw new IllegalArgumentException("Routing array required");
        managedDns=ipv6||json.has("local_proxy")||encryptedDnsHost!=null||json.has("routing_rules")||json.has("default_action")||json.has("dns_rules");
        excludedApps=strings(json,"excluded_apps");includedApps=strings(json,"included_apps");
        if(!excludedApps.isEmpty()&&!includedApps.isEmpty())throw new IllegalArgumentException("Choose included OR excluded apps");
        if(excludedApps.size()+includedApps.size()>512)throw new IllegalArgumentException("Too many apps");
        controlApp=json.has("control_app")?json.getString("control_app"):null;
        if(controlApp!=null&&(!McrfAppScope.valid(controlApp)||!includedApps.contains(controlApp)))throw new IllegalArgumentException("Missing host control application in include scope");
        List<Ipv4Routes.Prefix> prefixes=new ArrayList<>();
        Ipv4Routes.Prefix dns=Ipv4Routes.parse(dnsIpv4+"/32");
        for(String item:strings(json,"direct_ipv4")) {
            Ipv4Routes.Prefix prefix=Ipv4Routes.parse(item);
            if(prefix.contains(dns))throw new IllegalArgumentException("VPN DNS cannot be bypassed");
            prefixes.add(prefix);
        }
        Ipv4Routes.tunnelRoutes(prefixes);direct=Collections.unmodifiableList(prefixes);
        blockedIpv4=strings(json,"blocked_ipv4");
        if(blockedIpv4.size()>128)throw new IllegalArgumentException("At most 128 block prefixes");
        for(String text:blockedIpv4) {
            Ipv4Routes.Prefix block=Ipv4Routes.parse(text);
            if(block.contains(dns))throw new IllegalArgumentException("VPN DNS cannot be blocked");
            for(Ipv4Routes.Prefix bypass:direct)if(block.contains(bypass)||bypass.contains(block))
                throw new IllegalArgumentException("Overlapping direct/block rules need ordered routing, not supported yet");
        }
        JSONArray rules=json.optJSONArray("routing_rules");rules=rules==null?new JSONArray():new JSONArray(rules.toString());
        for(Ipv4Routes.Prefix prefix:direct)rules.put(new JSONObject().put("type","ip_cidr").put("value",prefix.toString()).put("action","direct"));
        for(String prefix:blockedIpv4)rules.put(new JSONObject().put("type","ip_cidr").put("value",prefix).put("action","block"));
        // Model2 can retain IPv6 rule terms with IPv6 capture OFF. Those terms
        // must not enable IPv6 packets/aliases or turn a DNS /0 union into an
        // unsupported setting; createDns/Builder still follow ipv6 explicitly.
        policy=new DomainPolicy(rules,json.optString("default_action","proxy"),version==2,version==2&&(json.optBoolean("owner_rules",false)||!flowSets.isEmpty()),ruleSets,flowSets);
        if(json.has("dns_rules")!=json.has("dns_upstreams"))throw new IllegalArgumentException("DNS rules and upstreams must be paired");
        ruleDnsPolicy=new DnsRulePolicy(json.has("dns_rules")?json.getJSONArray("dns_rules"):new JSONArray(),ruleSets);
        Map<String,DnsUpstreamPlan> upstreams=new LinkedHashMap<>();
        if(json.has("dns_upstreams")){
            JSONObject configured=json.getJSONObject("dns_upstreams");
            for(Iterator<String> keys=configured.keys();keys.hasNext();){
                String tag=keys.next();if(!tag.matches("[A-Za-z0-9_-]{1,64}")||upstreams.size()>=32)throw new IllegalArgumentException("DNS upstream capacity/tag");
                DnsUpstreamPlan plan=new DnsUpstreamPlan(configured.getJSONObject(tag));
                if(plan.usesIpv6()&&!ipv6)throw new IllegalArgumentException("IPv6 rule DNS requires capture");upstreams.put(tag,plan);
            }
        }
        ruleDnsPolicy.requireBound(upstreams.keySet());
        if(!upstreams.keySet().equals(ruleDnsPolicy.requiredTags()))throw new IllegalArgumentException("Unreferenced DNS upstream");
        ruleDnsUpstreams=Collections.unmodifiableMap(upstreams);
        localProxy=json.has("local_proxy")?new SteerLocalProxy(json.getJSONObject("local_proxy")):null;
    }
    /** Internal version2 owner path. Public module capability/host projection is
     * still separately gated; legacy version1 never instantiates IPv6 aliases. */
    public TunnelDns createDns(List<java.net.InetAddress> bootstrap,ProxyFlowRegistry proxyFlows)throws java.io.IOException {
        return createDns(bootstrap,proxyFlows,null);
    }
    public TunnelDns createDns(List<java.net.InetAddress> bootstrap,ProxyFlowRegistry proxyFlows,android.content.Context context)throws java.io.IOException {
        Ipv6DnsRoutes adapter=ipv6?Ipv6DnsRoutes.create(policy):null;
        try{return new TunnelDns(this,bootstrap,adapter,proxyFlows,globalDnsPlan!=null||policy.requiresOwner()||policy.requiresFlow()?new SocketOwnerLookup(context):null);}
        catch(java.io.IOException|RuntimeException failure){if(adapter!=null)adapter.close();throw failure;}
    }
    public void apply(VpnService.Builder builder) throws PackageManager.NameNotFoundException {
        builder.setMtu(SteerCore.TUN_MTU).addAddress("10.89.0.2",32).addDnsServer(managedDns?"10.89.0.1":dnsIpv4);
        if(ipv6)builder.addAddress(TUN_IPV6,126).addRoute("::",0);
        for(Ipv4Routes.Prefix prefix:Ipv4Routes.tunnelRoutes(managedDns?Collections.emptyList():direct))builder.addRoute(prefix.address(),prefix.bits);
        for(String app:excludedApps)builder.addDisallowedApplication(app);
        for(String app:includedApps)builder.addAllowedApplication(app);
    }
    @Override public void close(){if(localProxy!=null)localProxy.close();}
}
