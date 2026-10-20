package org.mcrf.steerprep;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pure app-side projection of captured MCRF settings. No Android, JNI, storage or network.
 * Success is snapshot compatibility only: the worker MUST still validate the original profile.
 */
public final class SteerMcrfSnapshotAdapter {
    private SteerMcrfSnapshotAdapter() {}
    public static final class Issue {
        public final String code, field;
        private Issue(String code,String field){this.code=code;this.field=field;}
    }
    public static final class Result {
        public final boolean supported;
        /** Non-null only on complete success. Pass verbatim to Engine.validate/tunPlanJson/run. */
        public final String settingsJson, inputSha256;
        public final List<Issue> unsupported;
        /** Explicit reasons why known inactive settings need no runtime translation. Originals stay in MCRF. */
        public final List<String> inactive;
        private Result(String settings,String hash,List<Issue> issues,List<String> notes){
            supported=issues.isEmpty();settingsJson=supported?settings:null;inputSha256=hash;
            unsupported=Collections.unmodifiableList(new ArrayList<>(issues));
            inactive=Collections.unmodifiableList(new ArrayList<>(notes));
        }
    }
    private static final class Check {
        final List<Issue> issues=new ArrayList<>();final List<String> notes=new ArrayList<>();
        Set<Integer> geoNeeded=Collections.emptySet();
        boolean nativeFlows;
        void fail(String code,String field){if(issues.size()<256)issues.add(new Issue(code,field));}
        void expect(JSONObject obj,String key,Object value,String path,String code){
            if(obj.has(key)&&!value.equals(obj.opt(key)))fail(code,path+"."+key);
        }
        // Compact schema: s string, b boolean, i exact integer, o object, a array, n object-or-null.
        void shape(JSONObject obj,String path,String fields,String required){
            Map<String,Character> schema=new LinkedHashMap<>();
            for(String field:fields.split(" ")){String[] pair=field.split(":");schema.put(pair[0],pair[1].charAt(0));}
            for(Iterator<String> keys=obj.keys();keys.hasNext();){String key=keys.next();Character kind=schema.get(key);
                if(kind==null){fail("UNKNOWN_FIELD",path+"."+key);continue;}
                Object v=obj.opt(key);boolean good=kind=='s'?v instanceof String:kind=='b'?v instanceof Boolean:
                    kind=='i'?v instanceof Integer||v instanceof Long:kind=='o'?v instanceof JSONObject:
                    kind=='a'?v instanceof JSONArray:v==JSONObject.NULL||v instanceof JSONObject;
                if(!good)fail("TYPE",path+"."+key);
            }
            if(!required.isEmpty())for(String key:required.split(" "))if(!obj.has(key))fail("MISSING_FIELD",path+"."+key);
        }
        void complete(JSONObject obj,String path,String fields){
            StringJoiner keys=new StringJoiner(" ");for(String item:fields.split(" "))keys.add(item.split(":")[0]);
            shape(obj,path,fields,keys.toString());
        }
        JSONObject object(JSONObject parent,String key){JSONObject v=parent.optJSONObject(key);return v==null?new JSONObject():v;}
        void emptyArray(JSONObject obj,String key,String path,String code){
            JSONArray a=obj.optJSONArray(key);if(a!=null&&a.length()>0)fail(code,path+"."+key);
        }
        void packages(JSONObject obj,String key){JSONArray a=obj.optJSONArray(key);if(a==null)return;
            if(a.length()>256)fail("APP_LIMIT","routing."+key);Set<String> found=new HashSet<>();
            for(int i=0;i<a.length();i++){Object v=a.opt(i);if(!(v instanceof String)||!((String)v).matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")||!found.add((String)v))fail("APP_PACKAGE","routing."+key+"["+i+"]");}
        }
        void dns(JSONObject value,String path,boolean active){
            shape(value,path,"provider:s protocol:s custom:s","provider protocol");
            if(!active)return;
            if(!Arrays.asList("UDP","DoH","DoT").contains(value.opt("protocol")))fail("DNS_PROTOCOL",path+".protocol");
            if(!Arrays.asList("Cloudflare","Google","Quad9","AdGuard","Яндекс","Comss.one","Свой").contains(value.opt("provider")))fail("DNS_PROVIDER",path+".provider");
            if("Свой".equals(value.opt("provider"))&&(!(value.opt("custom") instanceof String)||value.optString("custom").trim().isEmpty()))fail("DNS_ADDRESS",path+".custom");
        }
        void nativeAddress(JSONObject value,String path,int depth,int[] nodes)throws JSONException{
            if(depth>16||++nodes[0]>4096||depth==0&&value.toString().length()>262144){fail("NATIVE_MATCH_LIMIT",path);return;}
            if(value.has("invert")&&!(value.opt("invert") instanceof Boolean))fail("TYPE",path+".invert");
            if(value.has("type")&&!(value.opt("type") instanceof String)){fail("TYPE",path+".type");return;}
            String type=value.optString("type","default");
            if(type.equals("logical")){
                shape(value,path,"type:s mode:s rules:a invert:b","type mode rules");
                if(!Arrays.asList("and","or").contains(value.opt("mode")))fail("NATIVE_MATCH_MODE",path+".mode");
                JSONArray rows=value.optJSONArray("rules");
                if(rows==null||rows.length()<1||rows.length()>256){fail("NATIVE_MATCH_LIMIT",path+".rules");return;}
                for(int at=0;at<rows.length();at++){
                    JSONObject row=rows.optJSONObject(at);if(row==null)fail("TYPE",path+".rules["+at+"]");
                    else nativeAddress(row,path+".rules["+at+"]",depth+1,nodes);
                }
                return;
            }
            if(!type.equals("default"))fail("NATIVE_MATCH_TYPE",path+".type");
            Set<String> allowed=new HashSet<>(Arrays.asList("type","invert","domain","domain_suffix","domain_keyword","domain_regex","ip_cidr","rule_set",
                "source_ip_cidr","source_port","source_port_range","port","port_range","network","package_name"));
            int conditions=0;
            for(Iterator<String> it=value.keys();it.hasNext();){String key=it.next();
                if(!allowed.contains(key)){fail("NATIVE_MATCH_FIELD",path+"."+key);continue;}
                if(key.equals("type")||key.equals("invert"))continue;conditions++;
                Object raw=value.opt(key);
                if(key.equals("port")||key.equals("source_port")){
                    nativeFlows=true;JSONArray ports=raw instanceof JSONArray?(JSONArray)raw:new JSONArray().put(raw);
                    if(ports.length()<1||ports.length()>1024){fail("NATIVE_MATCH_LIMIT",path+"."+key);continue;}
                    for(int at=0;at<ports.length();at++){
                        Object port=ports.opt(at);
                        if(!(port instanceof Integer||port instanceof Long)||((Number)port).longValue()<0||((Number)port).longValue()>65535)fail("NATIVE_MATCH_PORT",path+"."+key+"["+at+"]");
                    }continue;
                }
                boolean range=key.equals("port_range")||key.equals("source_port_range");
                if(range||key.equals("source_ip_cidr")||key.equals("network")||key.equals("package_name"))nativeFlows=true;
                JSONArray values=raw instanceof String?new JSONArray().put(raw):value.optJSONArray(key);
                int limit=range?1024:key.equals("network")?2:key.equals("package_name")?512:key.equals("source_ip_cidr")?4096:
                    key.equals("rule_set")?64:key.equals("domain_regex")?128:key.equals("domain_keyword")?4096:100000;
                if(values==null||values.length()<1||values.length()>limit){fail("NATIVE_MATCH_LIMIT",path+"."+key);continue;}
                Set<String> unique=new HashSet<>();
                for(int at=0;at<values.length();at++){
                    Object item=values.opt(at);String field=path+"."+key+"["+at+"]";
                    if(!(item instanceof String)||((String)item).isEmpty()){fail("TYPE",field);continue;}
                    String text=(String)item;
                    if(range){
                        if(!text.matches("[0-9]{0,5}:[0-9]{0,5}")){fail("NATIVE_MATCH_PORT",field);continue;}
                        String[] ends=text.split(":",-1);for(String end:ends)if(!end.isEmpty()&&Integer.parseInt(end)>65535)fail("NATIVE_MATCH_PORT",field);
                    }
                    if(key.equals("network")&&(!Arrays.asList("tcp","udp").contains(text)||!unique.add(text)))fail("NATIVE_MATCH_NETWORK",field);
                    if(key.equals("package_name")&&(text.length()>255||!text.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")||!unique.add(text)))fail("NATIVE_MATCH_PACKAGE",field);
                    if(key.equals("source_ip_cidr")&&text.length()>128)fail("NATIVE_MATCH_LIMIT",field);
                    if(key.equals("rule_set")){
                        String ref=(String)item;
                        if(!ref.matches("mcrf-geo-(?:0|[1-9][0-9]?)")||!geoNeeded.contains(Integer.parseInt(ref.substring(9))))fail("GEO_BINDING",field);
                    }
                }
            }
            if(conditions==0)fail("NATIVE_MATCH_EMPTY",path);
        }
        JSONArray geoManifest(JSONObject snapshot,SteerGeoProjection.Plan plan)throws JSONException{
            JSONArray manifest=snapshot.optJSONArray("geoManifest");
            geoNeeded=plan==null?Collections.emptySet():plan.neededSelectors;
            if(manifest==null){if(!geoNeeded.isEmpty())fail("GEO_MANIFEST_REQUIRED","snapshot.geoManifest");return new JSONArray();}
            if(manifest.length()>64){fail("GEO_MANIFEST_LIMIT","snapshot.geoManifest");return new JSONArray();}
            Set<Integer> seen=new HashSet<>();long total=0;
            for(int at=0;at<manifest.length();at++){
                String path="snapshot.geoManifest["+at+"]";JSONObject row=manifest.optJSONObject(at);
                if(row==null){fail("TYPE",path);continue;}
                complete(row,path,"selector:i file:s sha256:s bytes:i kind:s code:s");
                Object id=row.opt("selector"),size=row.opt("bytes");
                if(!(id instanceof Integer||id instanceof Long)||((Number)id).longValue()<0||((Number)id).longValue()>63){fail("GEO_SELECTOR",path);continue;}
                int index=((Number)id).intValue();
                if(!seen.add(index)||!geoNeeded.contains(index)){fail("GEO_BINDING",path);continue;}
                JSONObject selector=plan.selector(index);
                if(!selector.getString("kind").equals(row.opt("kind"))||!selector.getString("code").equals(row.opt("code")))fail("GEO_BINDING",path);
                if(!row.optString("file").matches("[0-9a-f]{64}\\.json")||!row.optString("sha256").matches("[0-9a-f]{64}"))fail("GEO_IDENTITY",path);
                if(!(size instanceof Integer||size instanceof Long)||((Number)size).longValue()<1||((Number)size).longValue()>8L*1024*1024)fail("GEO_BYTES",path);
                else total+=((Number)size).longValue();
            }
            if(!seen.equals(geoNeeded))fail("GEO_BINDING","snapshot.geoManifest");
            if(total>8L*1024*1024)fail("GEO_BYTES","snapshot.geoManifest");
            return new JSONArray(manifest.toString());
        }
        void routing(JSONObject r,boolean newModel) throws JSONException {
            shape(r,"routing","version:i defaultTarget:s globalDns:o excludedApps:a vpnApps:a rules:a appMode:s advancedDns:o geoData:o remoteRouting:o","version defaultTarget globalDns excludedApps vpnApps rules");
            expect(r,"version",1,"routing","ROUTING_VERSION");
            if(!Arrays.asList("proxy","direct","block").contains(r.opt("defaultTarget")))fail("DEFAULT_TARGET","routing.defaultTarget");
            if(r.has("appMode")&&!(newModel?Arrays.asList("all","include"):Collections.singletonList("all")).contains(r.opt("appMode")))fail("APP_SCOPE","routing.appMode");
            packages(r,"excludedApps");packages(r,"vpnApps");if(!newModel)emptyArray(r,"vpnApps","routing","FORCED_APPS");
            else {
                JSONArray excluded=r.optJSONArray("excludedApps"),forced=r.optJSONArray("vpnApps");Set<Object> omitted=new HashSet<>();
                if(excluded!=null)for(int at=0;at<excluded.length();at++)omitted.add(excluded.opt(at));
                if(forced!=null)for(int at=0;at<forced.length();at++)if(omitted.contains(forced.opt(at)))fail("FORCED_APPS","routing.vpnApps["+at+"]");
                if("include".equals(r.opt("appMode"))&&(forced==null||forced.length()==0))fail("APP_SCOPE","routing.vpnApps");
            }
            dns(object(r,"globalDns"),"routing.globalDns",true);
            JSONArray rules=r.optJSONArray("rules");Set<String> ids=new HashSet<>();
            if(rules!=null){if(rules.length()>1024)fail("RULE_LIMIT","routing.rules");
                for(int i=0;i<rules.length();i++){String path="routing.rules["+i+"]";JSONObject rule=rules.optJSONObject(i);
                    if(rule==null){fail("TYPE",path);continue;}
                    shape(rule,path,"id:s name:s text:s mode:s target:s enabled:b invert:b builtinPreset:s dns:n presetIds:a nativeMatch:o","id name text mode target enabled");
                    String id=rule.optString("id");if(!id.matches("[a-zA-Z0-9_-]{1,64}")||!ids.add(id))fail("RULE_ID",path+".id");
                    if(rule.optString("name").trim().isEmpty()||rule.optString("name").length()>256)fail("RULE_NAME",path+".name");
                    if(Boolean.FALSE.equals(rule.opt("enabled"))){notes.add(path+": disabled; original row retained verbatim");continue;}
                    if(rule.has("nativeMatch")){
                        JSONObject match=rule.optJSONObject("nativeMatch");
                        if(!newModel||match==null||!"addresses".equals(rule.opt("mode"))||!rule.optString("text").trim().isEmpty()||rule.has("presetIds")||Boolean.TRUE.equals(rule.opt("invert"))||rule.has("dns"))fail("NATIVE_MATCH",path);
                        else nativeAddress(match,path+".nativeMatch",0,new int[]{0});
                    }
                    if(!rule.optString("builtinPreset").isEmpty())fail("PRESET",path+".builtinPreset");
                    if(rule.has("presetIds")){
                        JSONArray presets=rule.optJSONArray("presetIds");Set<Object> unique=new HashSet<>();
                        if(!newModel||!"addresses".equals(rule.opt("mode"))||presets==null||presets.length()<1||presets.length()>68)fail("PRESET",path+".presetIds");
                        else for(int at=0;at<presets.length();at++){
                            Object value=presets.opt(at);
                            if(!(value instanceof String)||!((String)value).matches("[0-9a-f]{16}")||!unique.add(value))fail("PRESET",path+".presetIds["+at+"]");
                        }
                    }
                    if(rule.has("dns")&&!rule.isNull("dns")){
                        if(!newModel)fail("RULE_DNS",path+".dns");
                        else dns(object(rule,"dns"),path+".dns",!"block".equals(rule.opt("target")));
                    }
                    if(!(newModel?Arrays.asList("addresses","any","apps","in-apps","except-apps"):Arrays.asList("addresses","any")).contains(rule.opt("mode")))fail("APP_CONDITION",path+".mode");
                    if(rule.optJSONObject("dns")!=null&&Arrays.asList("apps","in-apps","except-apps").contains(rule.opt("mode")))fail("RULE_DNS",path+".dns");
                    if(!Arrays.asList("proxy","direct","block").contains(rule.opt("target")))fail("OUTPUT",path+".target");
                    if(Boolean.TRUE.equals(rule.opt("invert"))&&(!"addresses".equals(rule.opt("mode"))||!"proxy".equals(rule.opt("target"))))fail("INVERT_CONDITION",path+".invert");
                    // Rule text grammar/domain/IP/profile validation remains mandatory in actual worker preflight.
                }
            }
            if(r.optJSONObject("advancedDns")!=null){JSONObject a=r.getJSONObject("advancedDns");String path="routing.advancedDns";
                complete(a,path,"enabled:b direct:o local:o final:s remoteNoAAAA:b directNoAAAA:b fakeIp:b fakeNoAAAA:b hosts:b answers:s cache:b capacity:i optimistic:b optimisticTimeout:s timeout:s strategy:s custom:s");
                expect(a,"enabled",false,path,"ADVANCED_DNS");dns(object(a,"direct"),path+".direct",false);dns(object(a,"local"),path+".local",false);
                if(Boolean.FALSE.equals(a.opt("enabled")))notes.add(path+": disabled by actual AdvancedDnsConfig.apply guard; known options retained in original");
            }
            if(r.optJSONObject("geoData")!=null){JSONObject g=r.getJSONObject("geoData");String path="routing.geoData";
                shape(g,path,"source:s geoipUrl:s geositeUrl:s selectors:a providerRules:a","source geoipUrl geositeUrl selectors");
                emptyArray(g,"selectors",path,"GEO_SELECTORS");emptyArray(g,"providerRules",path,"GEO_RULES");notes.add(path+": only empty selectors/providerRules allowed; source URLs preserved in original");
            }
            if(r.optJSONObject("remoteRouting")!=null){JSONObject v=r.getJSONObject("remoteRouting");String path="routing.remoteRouting";
                complete(v,path,"mirror:s mirrorUrl:s adblock:b adblockUrl:s autoRefresh:b intervalMinutes:i profiles:a");
                expect(v,"adblock",false,path,"REMOTE_ADBLOCK");expect(v,"autoRefresh",false,path,"REMOTE_REFRESH");emptyArray(v,"profiles",path,"REMOTE_PROFILES");
                notes.add(path+": no profiles/adblock/refresh; dormant fetch parameters preserved in original");
            }
        }
        void tun(JSONObject t,boolean dualStackModel){String path="tun";
            complete(t,path,"mtu:i ipv6:b address4:s address6:s bypassPrivate:b privateRanges:s bypassDirectIps:b mixedEnabled:b randomPort:b mixedPort:i httpProxy:b httpBypass:s shareLan:b lanPort:i dnsPort:i customInbounds:s");
            expect(t,"mtu",1500,path,"TUN_MTU");expect(t,"address4","10.89.0.2/32",path,"TUN_ADDRESS");
            if(!dualStackModel)expect(t,"ipv6",false,path,"TUN_UNSUPPORTED");
            else if(t.optBoolean("ipv6",false))expect(t,"address6","fdfe:dcba:9876::1/126",path,"TUN_ADDRESS6");
            if(!dualStackModel)expect(t,"mixedEnabled",false,path,"TUN_UNSUPPORTED");
            for(String k:Arrays.asList("bypassPrivate","bypassDirectIps","httpProxy","shareLan"))expect(t,k,false,path,"TUN_UNSUPPORTED");
            expect(t,"dnsPort",0,path,"DNS_LISTENER");expect(t,"customInbounds","",path,"CUSTOM_INBOUNDS");
            notes.add(dualStackModel?"tun: explicit IPv6 needs full native ::/0 capture; requested CONNECT listener is validated separately; unsupported effects are not dropped":"tun: inactive address6/privateRanges/mixed port/randomPort/httpBypass/LAN port remain saved; no listeners/bypass enabled");
        }
        JSONObject localProxy(JSONObject snapshot,JSONObject tun,boolean model)throws JSONException{
            JSONObject auth=snapshot.optJSONObject("localProxyAuth");
            boolean active=model&&tun.optBoolean("mixedEnabled",false);
            if(auth!=null){
                complete(auth,"localProxyAuth","enabled:b username:s password:s");
                for(String key:Arrays.asList("username","password")){
                    Object raw=auth.opt(key);if(!(raw instanceof String))continue;
                    String text=(String)raw;int count=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if(count<1||count>255)fail("PROXY_AUTH","localProxyAuth."+key);
                    for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i))||(key.equals("username")&&text.charAt(i)==':')){fail("PROXY_AUTH","localProxyAuth."+key);break;}
                }
            }
            if(!active)return null;
            if(auth==null){fail("PROXY_AUTH_REQUIRED","snapshot.localProxyAuth");return null;}
            if(!Boolean.TRUE.equals(auth.opt("enabled")))fail("PROXY_AUTH_REQUIRED","localProxyAuth.enabled");
            Object random=tun.opt("randomPort");if(!(random instanceof Boolean)){fail("PROXY_RANDOM_PORT","tun.randomPort");return null;}
            Object port=Boolean.TRUE.equals(random)?Integer.valueOf(0):tun.opt("mixedPort");
            if(!(port instanceof Integer||port instanceof Long)||(!Boolean.TRUE.equals(random)&&((Number)port).longValue()<1024)||((Number)port).longValue()>65535){fail("PROXY_PORT","tun.mixedPort");return null;}
            if(!issues.isEmpty())return null;
            return new JSONObject().put("port",port).put("username",auth.getString("username")).put("password",auth.getString("password"));
        }
        void tuning(JSONObject t){String path="coreTuning";
            complete(t,path,"certificateStore:s insecure:b ntpEnabled:b ntpServer:s ntpPort:i ntpInterval:s clashEnabled:b clashPort:i clashSecret:s apiEnabled:b apiPort:i apiSecret:s muxDefault:b muxProtocol:s muxConcurrency:i muxPadding:b fragmentDefault:b fragmentDelay:s fingerprint:s idleTimeout:s keepAlive:s streamWindow:s connectionWindow:s maxStreams:i initialPacketSize:i disablePmtu:b");
            expect(t,"certificateStore","system",path,"CERTIFICATE_STORE");
            for(String k:Arrays.asList("insecure","ntpEnabled","clashEnabled","apiEnabled","muxDefault","muxPadding","fragmentDefault","disablePmtu"))expect(t,k,false,path,"CORE_TUNING");
            for(String k:Arrays.asList("fingerprint","idleTimeout","keepAlive","streamWindow","connectionWindow"))expect(t,k,"",path,"CORE_TUNING");
            for(String k:Arrays.asList("maxStreams","initialPacketSize"))expect(t,k,0,path,"CORE_TUNING");
            notes.add("coreTuning: disabled NTP/API/mux/fragment companion parameters preserved in original, never emitted/logged");
        }
    }
    public static Result convert(String fullSnapshotJson){
        Check c=new Check();String hash="";JSONObject out=null;
        if(fullSnapshotJson==null){c.fail("SNAPSHOT_REQUIRED","snapshot");return new Result(null,hash,c.issues,c.notes);}
        try{org.mcrf.steerprep.json.SteerSnapshotJson.requireObject(fullSnapshotJson);}
        catch(org.mcrf.steerprep.json.SteerSnapshotJson.Rejected invalid){c.fail(invalid.code,"snapshot");return new Result(null,hash,c.issues,c.notes);}
        byte[] bytes=fullSnapshotJson.getBytes(StandardCharsets.UTF_8);
        try{StringBuilder b=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(bytes))b.append(String.format(Locale.ROOT,"%02x",v&255));hash=b.toString();}catch(Exception impossible){throw new AssertionError(impossible);}
        try{
            JSONObject s=new JSONObject(fullSnapshotJson);
            Object schemaValue=s.opt("schema");
            boolean dualStackModel=Integer.valueOf(2).equals(schemaValue)||Long.valueOf(2).equals(schemaValue);
            boolean legacyModel=Integer.valueOf(1).equals(schemaValue)||Long.valueOf(1).equals(schemaValue);
            String common="schema:i mode:s routing:o tun:o coreTuning:o additionalRequiredFeatures:a ";
            if(dualStackModel)c.shape(s,"snapshot",common+"acceptSteerDnsModel:b localProxyAuth:o hostPackage:s geoManifest:a ipv4Compatibility:b","schema mode routing tun coreTuning additionalRequiredFeatures acceptSteerDnsModel");
            else c.complete(s,"snapshot",common+"acceptSteerIpv4DnsModel:b");
            if(!legacyModel&&!dualStackModel)c.fail("SNAPSHOT_VERSION","snapshot.schema");
            c.expect(s,"mode","vpn","snapshot","PROXY_NOT_SUPPORTED");
            c.expect(s,dualStackModel?"acceptSteerDnsModel":"acceptSteerIpv4DnsModel",true,"snapshot",dualStackModel?"DNS_MODEL_CONSENT":"IPV4_DNS_CONSENT");
            JSONArray features=s.optJSONArray("additionalRequiredFeatures");
            if(features!=null)for(int i=0;i<features.length();i++)c.fail(features.opt(i) instanceof String?"ADDITIONAL_FEATURE":"TYPE","snapshot.additionalRequiredFeatures["+i+"]");
            JSONObject originalRouting=c.object(s,"routing"),r=originalRouting;SteerGeoProjection.Plan geoPlan=null;
            if(dualStackModel){
                if(originalRouting.has("geoData")&&originalRouting.optJSONObject("geoData")==null)c.fail("TYPE","routing.geoData");
                try{
                    geoPlan=SteerGeoProjection.plan(originalRouting);
                    r=new JSONObject(originalRouting.toString()).put("rules",geoPlan.rules());r.remove("geoData");
                    if(originalRouting.has("geoData"))c.notes.add("routing.geoData: original URLs/selectors retained; only ordered rules and exact private cache descriptors projected, no IO");
                }catch(IllegalArgumentException bad){c.fail("GEO_CONFIG","routing.geoData");}
            }
            JSONArray geoManifest=dualStackModel?c.geoManifest(s,geoPlan):new JSONArray();
            c.routing(r,dualStackModel);c.tun(c.object(s,"tun"),dualStackModel);c.tuning(c.object(s,"coreTuning"));
            boolean include=dualStackModel&&"include".equals(r.opt("appMode"));
            if(include){
                Object host=s.opt("hostPackage");
                if(!(host instanceof String)||((String)host).length()>255||!((String)host).matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))c.fail("HOST_CONTROL_PACKAGE","snapshot.hostPackage");
                JSONArray excluded=r.optJSONArray("excludedApps");
                if(excluded!=null)for(int at=0;at<excluded.length();at++)if(excluded.opt(at).equals(host))c.fail("HOST_CONTROL_EXCLUDED","routing.excludedApps["+at+"]");
            }else if(s.has("hostPackage"))c.fail("HOST_CONTROL_SCOPE","snapshot.hostPackage");
            JSONObject proxy=c.localProxy(s,c.object(s,"tun"),dualStackModel);
            // Pure normalization preserves the complete rule and authenticated
            // request settings. Main AND independently loaded worker negotiate
            // observed_control_request; this adapter never enables a capability.
            if(c.issues.isEmpty()){
                out=new JSONObject();for(String k:Arrays.asList("version","defaultTarget","globalDns","excludedApps","vpnApps","rules"))out.put(k,r.get(k));
                if(dualStackModel){
                    out.put("steerVersion",2).put("ipv6",c.object(s,"tun").getBoolean("ipv6")).put("appMode",r.optString("appMode","all"));
                    if(s.has("ipv4Compatibility"))out.put("ipv4Compatibility",s.getBoolean("ipv4Compatibility"));
                    if(include)out.put("hostPackage",s.getString("hostPackage"));
                }
                if(proxy!=null)out.put("localProxy",proxy);
                if(geoManifest.length()>0)out.put("geoManifest",geoManifest);
                c.notes.add(include?"routing.appMode: selected applications plus explicit host control participant; shared UID capture is UID-granular":"routing.appMode: absent means all; original exclusions retained");
            }
        }catch(JSONException invalid){c.fail("SNAPSHOT_JSON","snapshot");}
        return new Result(out==null?null:out.toString(),hash,c.issues,c.notes);
    }
}
