package org.mcrf.engines.steer;

import android.net.InetAddresses;
import android.net.Network;
import org.json.*;
import org.mcrf.engine.spi.v1.Engine;
import org.steer.core.*;
import java.net.*;
import java.util.*;

/** Separate APK entry candidate. Host owns trusted loading, worker, VPN/TUN and fd lifetimes. */
public final class SteerEngine implements Engine {
    private boolean used;
    private volatile boolean stopping;
    private volatile long activeGeneration;
    private TunnelDns dns;
    private VpnProxyOwner localProxy;
    private NativeRouteOwner routeBackend;
    private final android.content.Context context;
    private final WorkerRuleSets ruleSets;
    public SteerEngine() { this(null); }
    public SteerEngine(android.content.Context hostContext) { context=hostContext==null?null:hostContext.getApplicationContext();ruleSets=context==null?null:new WorkerRuleSets(context); }
    @Override public String capabilitiesJson() { return BuildConfig.CORE_CAPABILITIES; }
    private SteerConfiguration prepare(String url, String settings) throws JSONException {
        if(org.mcrf.steerprep.json.SteerSnapshotJson.contractVersion()!=1)throw new IllegalArgumentException("SNAPSHOT_GUARD_CONTRACT");
        org.mcrf.steerprep.json.SteerSnapshotJson.requireObject(settings);
        JSONObject input=new JSONObject(settings);
        if(input.optInt("steerVersion",1)==2){
            if(SteerCore.nativeRouterSchema()!=2)throw new IllegalArgumentException("NATIVE_ROUTER_SCHEMA_MISMATCH");
            // Main's earlier asset inspection cannot authorize a different
            // module installed before this worker acquired its usage fence.
            JSONObject caps=new JSONObject(BuildConfig.CORE_CAPABILITIES);
            Object model=caps.opt("mcrf_snapshot_model");
            if(!(model instanceof Integer||model instanceof Long)||((Number)model).longValue()!=2)
                throw new IllegalArgumentException("SNAPSHOT_MODEL_NOT_ADVERTISED");
            if(input.optBoolean("ipv6",false)&&!"capture_v6_native".equals(caps.opt("ipv6")))
                throw new IllegalArgumentException("IPV6_NOT_ADVERTISED");
            if(input.optBoolean("ipv4Compatibility",false)&&!"managed_dns_a_only".equals(caps.opt("ipv4_compatibility")))
                throw new IllegalArgumentException("IPV4_DNS_NOT_ADVERTISED");
            if(input.has("localProxy")&&!"authenticated_http_socks_vpn".equals(caps.opt("local_proxy")))
                throw new IllegalArgumentException("LOCAL_PROXY_NOT_ADVERTISED");
            if(input.has("geoManifest")&&!"owned_pinned_geo".equals(caps.opt("geo_rules")))
                throw new IllegalArgumentException("GEO_RULES_NOT_ADVERTISED");
            JSONArray rules=input.optJSONArray("rules");
            if(rules!=null)for(int i=0;i<rules.length();i++){
                JSONObject rule=rules.getJSONObject(i);
                if(rule.optBoolean("enabled",true)&&rule.has("nativeMatch")){
                    if(!"owned_pinned_address".equals(caps.opt("native_address_rules")))throw new IllegalArgumentException("NATIVE_ADDRESS_RULES_NOT_ADVERTISED");
                    JSONObject match=rule.optJSONObject("nativeMatch");
                    org.mcrf.steerprep.json.SteerNativeFlowShape.Requirements required=org.mcrf.steerprep.json.SteerNativeFlowShape.inspect(match);
                    if(required.flow&&!"original_socket_tuple".equals(caps.opt("per_flow_routing")))throw new IllegalArgumentException("ORIGINAL_SOCKET_FLOW_NOT_ADVERTISED");
                    if(required.owner&&!"original_socket_uid".equals(caps.opt("per_app_routing")))throw new IllegalArgumentException("ORIGINAL_SOCKET_OWNER_NOT_ADVERTISED");
                    if(required.flow&&input.has("localProxy")&&
                            (!"observed_control_request".equals(caps.opt("local_proxy_flow"))||
                             !"original_socket_uid".equals(caps.opt("per_app_routing"))))
                        throw new IllegalArgumentException("ORIGINAL_PROXY_FLOW_NOT_ADVERTISED");
                }
                if(rule.optBoolean("enabled",true)&&rule.has("presetIds")&&
                    !"owned_srs_address".equals(caps.opt("preset_rules")))throw new IllegalArgumentException("PRESET_RULES_NOT_ADVERTISED");
                if(rule.optBoolean("enabled",true)&&!"block".equals(rule.optString("target"))&&rule.optJSONObject("dns")!=null&&
                        !"owned_udp_doh_dot".equals(caps.opt("per_rule_dns")))throw new IllegalArgumentException("RULE_DNS_NOT_ADVERTISED");
            }
        }
        if(input.has("geoManifest")&&(input.optInt("steerVersion",1)!=2||input.optJSONArray("geoManifest")==null))throw new IllegalArgumentException("GEO_MANIFEST_FORMAT");
        McrfPreparation.RuleSets bound=null;
        try{if(ruleSets!=null)bound=ruleSets.bind(input.optJSONArray("geoManifest"));}
        catch(java.io.IOException unavailable){throw new IllegalArgumentException("RULESET_PREPARATION_UNAVAILABLE");}
        SteerConfiguration prepared=McrfPreparation.prepare(url,input,bound);
        try{
            if(prepared.globalDnsPlan!=null&&context==null)throw new IllegalArgumentException("HOST_SOCKET_OWNER_CONTEXT_REQUIRED");
            if(prepared.policy.requiresOwner()&&(context==null||!"original_socket_uid".equals(new JSONObject(BuildConfig.CORE_CAPABILITIES).opt("per_app_routing"))))
                throw new IllegalArgumentException("ORIGINAL_SOCKET_OWNER_NOT_ADVERTISED");
            if(prepared.policy.requiresFlow()&&(context==null||!"original_socket_tuple".equals(new JSONObject(BuildConfig.CORE_CAPABILITIES).opt("per_flow_routing"))))
                throw new IllegalArgumentException("ORIGINAL_SOCKET_FLOW_NOT_ADVERTISED");
            if(prepared.policy.requiresFlow()&&prepared.localProxy!=null){
                JSONObject actual=new JSONObject(BuildConfig.CORE_CAPABILITIES);
                if(context==null||!"observed_control_request".equals(actual.opt("local_proxy_flow"))||
                        !"original_socket_uid".equals(actual.opt("per_app_routing")))
                    throw new IllegalArgumentException("ORIGINAL_PROXY_FLOW_NOT_ADVERTISED");
            }
            if(prepared.controlApp!=null&&(context==null||!prepared.controlApp.equals(context.getPackageName())))
                throw new IllegalArgumentException("HOST_CONTROL_IDENTITY_MISMATCH");
            return prepared;
        }catch(JSONException|RuntimeException failure){prepared.close();throw failure;}
    }
    @Override public Validation validate(String url, String settings) {
        try (SteerConfiguration checked=prepare(url,settings)) { return new Validation(true, "", ""); }
        catch (McrfPreparation.Unsupported e) { return new Validation(false, e.code, "settings"); }
        catch (JSONException | IllegalArgumentException e) { return new Validation(false, "PROFILE_OR_CONFIG", "settings"); }
        catch (LinkageError e) { return new Validation(false, "NATIVE_LOAD", "engine"); }
    }
    @Override public String tunPlanJson(String url, String settings) {
        try (SteerConfiguration c=prepare(url,settings)) {
            JSONArray addresses=new JSONArray().put("10.89.0.2/32"),routes=new JSONArray().put("0.0.0.0/0");
            if(c.ipv6){addresses.put(SteerConfiguration.TUN_IPV6+"/126");routes.put("::/0");}
            JSONObject plan=new JSONObject().put("schema",c.ipv6?2:1).put("mtu",SteerCore.TUN_MTU)
                .put("addresses",addresses)
                .put("routes",routes)
                .put("dns",new JSONArray().put("10.89.0.1"))
                .put("excludedApps",new JSONArray(c.excludedApps))
                .put("ipv6",c.ipv6?"capture_v6_native":"block_for_captured_apps").put("hostMustBeInsideVpn",true)
                .put("requiresVpnNetworkHandle",true);
            if(c.controlApp!=null)plan.put("includedApps",new JSONArray(c.includedApps)).put("controlApp",c.controlApp);
            return plan.toString();
        } catch (JSONException e) { throw new IllegalArgumentException("CONFIG_JSON",e); }
    }
    @Override public void run(Request request, Host host) throws Exception {
        if (host == null) throw new IllegalArgumentException("HOST_REQUIRED");
        if (request.mode != Mode.VPN) throw new IllegalArgumentException("PROXY_NOT_SUPPORTED");
        if (request.tunAcquisition != TunAcquisition.PROVIDED || request.borrowedTunFd < 0)
            throw new IllegalArgumentException("PROVIDED_TUN_REQUIRED");
        synchronized(this) {
            if (used || stopping) throw new IllegalStateException("FRESH_WORKER_REQUIRED");
            used = true;
            activeGeneration = request.generation;
        }
        try(SteerConfiguration config=prepare(request.serverUrl,request.settingsJson)){
        if (request.vpnNetworkHandle == 0 || host.packageName() == null
                || config.excludedApps.contains(host.packageName())
                || (!config.includedApps.isEmpty()&&!config.includedApps.contains(host.packageName()))
                || (config.controlApp!=null&&!config.controlApp.equals(host.packageName()))) throw new IllegalArgumentException("HOST_VPN_NETWORK_REQUIRED");
        Ipv4Routes.parse(request.dialIpv4+"/32");
        List<InetAddress> bootstrap = new ArrayList<>();
        if (config.encryptedDnsHost != null) {
            List<String> addresses = config.encryptedDnsBootstrap.isEmpty()
                    ? Arrays.asList(request.dnsBootstrapIpv4()) : config.encryptedDnsBootstrap;
            for (String address : addresses) {
                InetAddress ip = InetAddresses.parseNumericAddress(address);
                if (!(ip instanceof Inet4Address)&&!(config.ipv6&&config.globalDnsPlan!=null&&ip instanceof java.net.Inet6Address))throw new IllegalArgumentException("DNS_BOOTSTRAP_CAPTURE_REQUIRED");
                bootstrap.add(ip);
            }
        }
        TunnelDns activeDns;int announcedProxyPort;
        try {
            synchronized(this) {
                if (stopping || host.stopping(request.generation)) throw new IllegalStateException("GENERATION_STOPPING");
                ProxyFlowRegistry flows=config.localProxy==null?null:new ProxyFlowRegistry(request.generation);
                try{dns=config.createDns(bootstrap,flows,context);}catch(Exception failure){if(flows!=null)flows.close();throw failure;}
                Network vpn=Network.fromNetworkHandle(request.vpnNetworkHandle);
                dns.attachVpn(vpn);
                if(config.localProxy!=null)localProxy=config.localProxy.openProtected(vpn,dns,flows,
                    fd->host.prepareSocket(request.generation,fd),()->stopping||host.stopping(request.generation));
                announcedProxyPort=localProxy==null?0:localProxy.port();
                activeDns = dns;
            }
            String metadata=new JSONObject(request.settingsJson).optInt("steerVersion",1)==2?
                new JSONObject().put("schema",1).put("localProxyPort",announcedProxyPort).toString():"";
            host.state(request.generation,"STARTING",metadata);
            if (stopping || host.stopping(request.generation)) return;
            int code = SteerCore.run(request.serverUrl,request.dialIpv4,request.stateDirectory,
                    request.borrowedTunFd,fd -> prepareSocket(request,host,fd),
                    new String[0],activeDns,activeDns.port(),config.dnsIpv4);
            host.state(request.generation,"WORKER_EXIT_REQUIRED","NATIVE_RETURN_"+code);
        } finally {
            requestStop();
            // No STOPPED/cleanup ACK: only host-observed worker death releases this generation.
        }
        }
    }
    private boolean prepareSocket(Request request,Host host,int fd) {
        // The synchronous protect/bind ACK may race local STOP or host cancellation.
        // Refuse a late ACK so native closes the socket instead of connecting it.
        return !stopping && !host.stopping(request.generation)
                && host.prepareSocket(request.generation,fd)
                && !stopping && !host.stopping(request.generation);
    }
    /** Additive, capability-gated entry. Host invokes this only in a fresh
     * dedicated probe worker and observes worker death before publishing it. */
    public long probeHttps(String profile,String settings,String dialIpv4,String directory,long generation,Host host) throws Exception {
        if(host==null||generation<=0||!"native_https_204_fresh_worker_v1".equals(
                new JSONObject(BuildConfig.CORE_CAPABILITIES).opt("independent_probe"))||SteerCore.nativeProbeSchema()!=1)
            throw new IllegalArgumentException("INDEPENDENT_PROBE_NOT_ADVERTISED");
        JSONObject input=new JSONObject(settings);
        Set<String> keys=new HashSet<>(Arrays.asList("schema","url","targetIp","timeoutMs"));
        Iterator<String> names=input.keys();while(names.hasNext())if(!keys.remove(names.next()))throw new IllegalArgumentException("PROBE_FIELDS");
        if(!keys.isEmpty()||!Integer.valueOf(1).equals(input.opt("schema"))||!(input.opt("timeoutMs") instanceof Integer)||
                !(input.opt("url") instanceof String)||!(input.opt("targetIp") instanceof String))throw new IllegalArgumentException("PROBE_FIELDS");
        String url=input.getString("url");URI parsed=new URI(url);
        if(url.length()>2304||!url.matches("[\\x21-\\x7e]+")||!"https".equals(parsed.getScheme())||parsed.getHost()==null||
                parsed.getRawUserInfo()!=null||parsed.getRawFragment()!=null||(parsed.getPort()!=-1&&parsed.getPort()!=443)||
                !parsed.getHost().matches("[A-Za-z0-9.-]{1,253}"))throw new IllegalArgumentException("PROBE_HTTPS_REQUIRED");
        String path=parsed.getRawPath();if(path==null||path.isEmpty())path="/";
        if(parsed.getRawQuery()!=null)path+="?"+parsed.getRawQuery();
        int timeout=input.getInt("timeoutMs");
        if(path.length()>2048||!path.startsWith("/")||timeout<1||timeout>30000)throw new IllegalArgumentException("PROBE_INPUT");
        InetAddresses.parseNumericAddress(input.getString("targetIp"));
        if(!(InetAddresses.parseNumericAddress(dialIpv4) instanceof Inet4Address)||SteerCore.serverHost(profile)==null)
            throw new IllegalArgumentException("PROBE_PROFILE_OR_DIAL");
        synchronized(this){
            if(used||stopping)throw new IllegalStateException("FRESH_WORKER_REQUIRED");
            used=true;activeGeneration=generation;
        }
        try {
            if(stopping||host.stopping(generation))throw new IllegalStateException("GENERATION_STOPPING");
            long delay=SteerCore.probeHttps(profile,dialIpv4,input.getString("targetIp"),parsed.getHost(),path,directory,timeout,
                fd->!stopping&&!host.stopping(generation)&&host.prepareSocket(generation,fd)&&!stopping&&!host.stopping(generation));
            if(delay< -1||delay>timeout||stopping||host.stopping(generation))return -1;
            return delay;
        } finally {requestStop();}
    }
    /** SOURCE-ONLY internal role, separate from PROXY/VPN mode or user-facing
     * local proxy. It remains unreachable with the unchanged public/base caps.
     * Parent resolves numeric targets through each chosen rule's own DNS and
     * must retire this exact fresh worker before reusing its slot/module lease. */
    public String openRoute(String profile,String settings,String dialIpv4,String directory,long generation,Host host)throws Exception {
        if(host==null||generation<=0||!"native_protocol_internal_socks_1".equals(new JSONObject(BuildConfig.CORE_CAPABILITIES).opt("route_backend"))||SteerCore.nativeRouteSchema()!=1)
            throw new IllegalArgumentException("ROUTE_BACKEND_NOT_ADVERTISED");
        if(settings==null||settings.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024)throw new IllegalArgumentException("ROUTE_SETTINGS");
        JSONObject input=new JSONObject(settings);
        Set<String> fields=new HashSet<>(Arrays.asList("schema","username","password","destinationDns"));
        Iterator<String> names=input.keys();while(names.hasNext())if(!fields.remove(names.next()))throw new IllegalArgumentException("ROUTE_FIELDS");
        if(!fields.isEmpty()||!Integer.valueOf(1).equals(input.opt("schema"))||
                !(input.opt("username") instanceof String)||!(input.opt("password") instanceof String)||
                !input.getString("username").matches("[0-9a-f]{32}")||!input.getString("password").matches("[0-9a-f]{64}")||
                !"numeric_resolved_by_dispatcher".equals(input.opt("destinationDns"))||
                !(InetAddresses.parseNumericAddress(dialIpv4) instanceof Inet4Address)||SteerCore.serverHost(profile)==null)
            throw new IllegalArgumentException("ROUTE_INPUT");
        synchronized(this){
            if(used||stopping)throw new IllegalStateException("FRESH_WORKER_REQUIRED");
            used=true;activeGeneration=generation;
        }
        byte[] user=input.getString("username").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] password=input.getString("password").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        NativeRouteOwner pending=null;
        try {
            if(stopping||host.stopping(generation))throw new IllegalStateException("GENERATION_STOPPING");
            pending=new NativeRouteOwner(profile,dialIpv4,directory,
                fd->!stopping&&!host.stopping(generation)&&host.prepareSocket(generation,fd)&&!stopping&&!host.stopping(generation),
                user,password,()->stopping||host.stopping(generation));
            synchronized(this){
                if(stopping||host.stopping(generation))throw new IllegalStateException("GENERATION_STOPPING");
                routeBackend=pending;pending=null;
                return new JSONObject().put("schema",1).put("port",routeBackend.port()).toString();
            }
        }finally{if(pending!=null)pending.close();Arrays.fill(user,(byte)0);Arrays.fill(password,(byte)0);}
    }
    @Override public synchronized void requestStop() {
        stopping = true;
        if(used)SteerCore.cancelTLS();
        if(routeBackend!=null){routeBackend.close();routeBackend=null;}
        if(localProxy!=null){localProxy.close();localProxy=null;}
        if (dns != null) { dns.close(); dns = null; }
    }
    @Override public Counters counters() {
        long[] values = SteerCore.counters();
        return new Counters(values[0],values[1],values[2]);
    }
    @Override public ControlReply control(ControlRequest request) {
        if(request.generation!=activeGeneration)return new ControlReply("STALE_GENERATION",null);
        return Engine.super.control(request);
    }
}
