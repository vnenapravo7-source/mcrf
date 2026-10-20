package org.steer.core;
import org.json.*;
import java.net.URI;
import java.util.*;
/** Worker-side normalization of the versioned host routing snapshot. */
public final class McrfPreparation {
    private McrfPreparation() {}
    public interface RuleSets {
        SrsAddressSet load(String id)throws java.io.IOException;
        default boolean matchesManifest(JSONArray manifest){return false;}
        default SrsAddressSet compileNative(JSONObject match)throws java.io.IOException{throw new java.io.IOException("Native match resolver unavailable");}
        default NativeFlowMatch compileNativeFlow(JSONObject match)throws java.io.IOException{throw new java.io.IOException("Original flow resolver unavailable");}
    }
    public static final class Unsupported extends IllegalArgumentException {
        public final String code;
        Unsupported(String code,String message){super(message);this.code=code;}
    }
    private static Unsupported unsupported(String code,String message){return new Unsupported(code,message);}
    private static void dns(JSONObject settings,JSONObject result,boolean ipv6) throws JSONException {
        JSONObject dns=settings.optJSONObject("globalDns");
        if(dns==null)throw unsupported("DNS_CONFIG","Нужны настройки DNS MCRF.");
        for(Iterator<String> keys=dns.keys();keys.hasNext();)if(!Arrays.asList("provider","protocol","custom").contains(keys.next()))throw unsupported("DNS_FIELDS","Дополнительные настройки DNS пока не перенесены.");
        String protocol=dns.optString("protocol","DoH"),provider=dns.optString("provider","Cloudflare"),host,ip;
        if(!protocol.equals("UDP")&&!protocol.equals("DoH")&&!protocol.equals("DoT"))throw unsupported("DNS_PROTOCOL","Пока поддержаны UDP DNS, DoH и DoT.");
        switch(provider) {
            case "Cloudflare":host="cloudflare-dns.com";ip="1.1.1.1";break;
            case "Google":host="dns.google";ip="8.8.8.8";break;
            case "Quad9":host="dns.quad9.net";ip="9.9.9.9";break;
            case "AdGuard":host="dns.adguard-dns.com";ip="94.140.14.14";break;
            case "Яндекс":host="common.dot.dns.yandex.net";ip="77.88.8.8";break;
            case "Comss.one":host="dns.comss.one";ip="195.133.25.16";break;
            case "Свой":
                try {
                    String scheme=protocol.equals("DoH")?"https":protocol.equals("DoT")?"tls":"udp",raw=dns.getString("custom").trim();
                    URI uri=new URI(raw.contains("://")?raw:scheme+"://"+raw);
                    if(!scheme.equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException();
                    if(uri.getHost().contains(":")&&!ipv6)throw new IllegalArgumentException("IPv6 DNS requires explicit capture");
                    if(protocol.equals("DoH")){
                        String path=uri.getRawPath();
                        if(path==null||path.isEmpty()||path.equals("/"))uri=new URI("https",null,uri.getHost(),uri.getPort(),"/dns-query",null,null);
                        result.put("doh_url",uri.toString());return;
                    }
                    if(protocol.equals("DoT")){
                        if(uri.getPort()==0||uri.getPort()==53||uri.getPort()>65535
                            ||(uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()&&!uri.getRawPath().equals("/")))throw new IllegalArgumentException();
                        result.put("dot_url",uri.toString());return;
                    }
                    if((uri.getPort()!=-1&&uri.getPort()!=53)||(uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()&&!uri.getRawPath().equals("/")))throw new IllegalArgumentException();
                    byte[] address=DnsUpstreamPlan.numeric(uri.getHost());result.put(address.length==16?"dns_ipv6":"dns_ipv4",DnsUpstreamPlan.normalizedHost(uri.getHost()));return;
                }catch(Exception invalid){throw unsupported("DNS_ADDRESS","Некорректный адрес DNS для Steer.");}
            default:throw unsupported("DNS_PROVIDER","Этот режим DNS пока не перенесён в Steer.");
        }
        if(protocol.equals("DoH"))result.put("doh_url","https://"+host+"/dns-query").put("doh_bootstrap",new JSONArray().put(ip));
        else if(protocol.equals("DoT"))result.put("dot_url","tls://"+host+":853").put("dot_bootstrap",new JSONArray().put(ip));
        else result.put("dns_ipv4",ip);
    }
    public static SteerConfiguration prepare(String originalServerUrl,JSONObject snapshot) throws JSONException {
        return prepare(originalServerUrl,snapshot,null);
    }
    public static SteerConfiguration prepare(String originalServerUrl,JSONObject snapshot,RuleSets resolver) throws JSONException {
        Set<String> top=new HashSet<>(Arrays.asList("version","defaultTarget","globalDns","excludedApps","vpnApps","rules","steerVersion","ipv6","localProxy","appMode","hostPackage","geoManifest","ipv4Compatibility"));
        for(Iterator<String> keys=snapshot.keys();keys.hasNext();)if(!top.contains(keys.next()))throw unsupported("CONFIG_FIELDS","Дополнительные настройки MCRF пока не перенесены.");
        for(String key:Arrays.asList("rules","vpnApps","excludedApps"))if(snapshot.has(key)&&snapshot.optJSONArray(key)==null)throw unsupported("CONFIG_FORMAT","Некорректный формат списков.");
        if(snapshot.optInt("version",1)!=1)throw unsupported("CONFIG_VERSION","Неизвестная версия маршрутов.");
        int model=snapshot.optInt("steerVersion",1);
        if(snapshot.has("steerVersion")){
            Object value=snapshot.opt("steerVersion");
            if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()!=model)throw unsupported("STEER_VERSION","Неверный тип версии Steer.");
        }
        if(model!=1&&model!=2)throw unsupported("STEER_VERSION","Неизвестный формат движка Steer.");
        if(snapshot.has("geoManifest")){
            JSONArray manifest=snapshot.optJSONArray("geoManifest");
            if(model!=2||manifest==null||manifest.length()>64||resolver==null||!resolver.matchesManifest(manifest))
                throw unsupported("GEO_MANIFEST","Нужен точный подготовленный снимок геоданных новой модели Steer.");
        }
        if(model==1&&(snapshot.has("appMode")||snapshot.has("hostPackage")))throw unsupported("APP_SCOPE","Область приложений требует новой модели Steer.");
        McrfAppScope appScope=model==2?new McrfAppScope(snapshot):null;
        if(snapshot.has("ipv6")&&(model!=2||!(snapshot.opt("ipv6") instanceof Boolean)))throw unsupported("IPV6_MODEL","Для IPv6 нужен явный новый формат Steer.");
        boolean ipv6=model==2&&snapshot.optBoolean("ipv6",false);
        if(snapshot.has("ipv4Compatibility")&&(model!=2||!(snapshot.opt("ipv4Compatibility") instanceof Boolean)))
            throw unsupported("IPV4_DNS_MODEL","Для IPv4-совместимости нужен явный логический параметр новой модели Steer.");
        if(snapshot.has("localProxy")&&(model!=2||snapshot.optJSONObject("localProxy")==null))throw unsupported("PROXY_MODEL","Локальный прокси требует нового формата Steer.");
        String defaultTarget=snapshot.optString("defaultTarget","proxy");
        if(!Arrays.asList("proxy","direct","block").contains(defaultTarget))throw unsupported("DEFAULT_TARGET","Неизвестный основной маршрут.");
        if(model!=2&&snapshot.optJSONArray("vpnApps")!=null&&snapshot.getJSONArray("vpnApps").length()!=0)throw unsupported("FORCED_APPS","Приоритет приложений «только VPN» требует новой модели Steer.");
        JSONObject result=new JSONObject().put("version",model).put("url",originalServerUrl).put("default_action",defaultTarget);dns(snapshot,result,ipv6);
        if(model==2)result.put("ipv6",ipv6).put("ipv4_only_dns",snapshot.optBoolean("ipv4Compatibility",false));
        if(snapshot.has("localProxy"))result.put("local_proxy",new JSONObject(snapshot.getJSONObject("localProxy").toString()));
        if(appScope!=null){
            result.put("excluded_apps",new JSONArray(appScope.excluded));
            if(appScope.controlApp!=null)result.put("included_apps",new JSONArray(appScope.included)).put("control_app",appScope.controlApp);
        }else if(snapshot.has("excludedApps"))result.put("excluded_apps",new JSONArray(snapshot.getJSONArray("excludedApps").toString()));
        JSONArray entries=new JSONArray(),dnsRules=new JSONArray(),rules=snapshot.optJSONArray("rules");
        if(rules!=null&&rules.length()>1024)throw unsupported("RULE_LIMIT","Не больше1024 правил на один снимок Steer.");
        JSONObject dnsUpstreams=new JSONObject();
        Map<String,String> dnsTags=new LinkedHashMap<>();
        Map<String,SrsAddressSet> ruleSets=new LinkedHashMap<>();
        Map<String,NativeFlowMatch> flowSets=new LinkedHashMap<>();
        int packagedCount=0;
        boolean ownerRules=false;
        JSONArray forced=snapshot.optJSONArray("vpnApps");
        if(forced!=null&&forced.length()>0){
            if(forced.length()>256)throw unsupported("FORCED_APPS","Не больше256 приложений только VPN.");
            Set<String> selected=new LinkedHashSet<>(),excluded=new HashSet<>();
            JSONArray omitted=snapshot.optJSONArray("excludedApps");if(omitted!=null)for(int at=0;at<omitted.length();at++)excluded.add(omitted.getString(at));
            for(int at=0;at<forced.length();at++){
                String pkg=forced.getString(at);
                if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")||!selected.add(pkg)||excluded.contains(pkg))
                    throw unsupported("FORCED_APPS","Некорректное, повторное или исключённое приложение только VPN.");
            }
            // Same precedence as RouteRules.apply: after managed DNS capture,
            // before every user list/default route. Original settings retained.
            entries.put(new JSONObject().put("type","group").put("mode","apps").put("apps",new JSONArray(selected))
                .put("entries",new JSONArray()).put("action","proxy"));ownerRules=true;
        }
        if(appScope!=null&&appScope.controlOnlyHost){
            // Control aliases bypass ordinary app policy by exact registration.
            // Other host traffic is not silently promoted to a selected proxy app.
            // Forced selected packages (including shared-UID siblings) take priority.
            entries.put(new JSONObject().put("type","group").put("mode","apps")
                .put("apps",new JSONArray().put(appScope.controlApp)).put("entries",new JSONArray()).put("action","direct"));
            ownerRules=true;
        }
        if(rules!=null)for(int i=0;i<rules.length();i++) {
            JSONObject row=rules.getJSONObject(i);
            if(row.has("enabled")&&!(row.opt("enabled") instanceof Boolean))throw unsupported("RULE_ENABLED","Нужен логический признак включения правила.");
            if(!row.optBoolean("enabled",true))continue;
            Set<String> known=new HashSet<>(Arrays.asList("id","name","text","mode","target","enabled","invert","builtinPreset","dns","presetIds","nativeMatch"));
            for(Iterator<String> keys=row.keys();keys.hasNext();)if(!known.contains(keys.next()))throw unsupported("RULE_FIELDS","Дополнительные параметры маршрута пока не перенесены.");
            if(!row.optString("builtinPreset","").isEmpty())throw unsupported("PRESET","Встроенные списки пока не перенесены.");
            if(row.has("dns")&&!row.isNull("dns")&&(model!=2||row.optJSONObject("dns")==null))throw unsupported("RULE_DNS","Отдельный DNS требует новой модели Steer и корректного объекта.");
            String mode=row.optString("mode","any"),action=row.optString("target","proxy");
            if(!Arrays.asList("addresses","any","apps","in-apps","except-apps").contains(mode)||
                    model!=2&&!mode.equals("addresses")&&!mode.equals("any"))throw unsupported("APP_CONDITION","Условия приложений требуют новой модели Steer.");
            if(!Arrays.asList("proxy","direct","block").contains(action))throw unsupported("OUTPUT","Этот выход пока недоступен.");
            if(row.has("invert")&&!(row.get("invert") instanceof Boolean))throw unsupported("INVERT_FORMAT","Нужен логический параметр инверсии.");
            boolean invert=row.optBoolean("invert",false);
            if(invert&&(!mode.equals("addresses")||!action.equals("proxy")))throw unsupported("INVERT_CONDITION","Инверсия доступна только для VPN и условия «Домены/IP».");
            JSONArray conditions=new JSONArray();
            if(row.has("nativeMatch")){
                JSONObject match=row.optJSONObject("nativeMatch");
                if(model!=2||resolver==null||match==null||!mode.equals("addresses")||!row.getString("text").trim().isEmpty()||row.has("presetIds")||invert||row.has("dns"))
                    throw unsupported("NATIVE_MATCH","Точное условие нельзя смешивать с обычным списком или неподдержанным режимом.");
                String key="native-"+i;
                org.mcrf.steerprep.json.SteerNativeFlowShape.Requirements required;
                try{required=org.mcrf.steerprep.json.SteerNativeFlowShape.inspect(match);}
                catch(IllegalArgumentException invalid){throw unsupported("NATIVE_MATCH","Точное условие имеет неподдержанную структуру. Исходные настройки сохранены.");}
                // No synthetic TUN tuple is constructed for a proxy request.
                // The separately negotiated runtime carries observed control/data
                // sockets and the requested destination to matchesProxy().
                try{
                    if(required.flow){
                        NativeFlowMatch set=resolver.compileNativeFlow(match);
                        if(set==null||!set.requiresFlow()||set.requiresOwner()!=required.owner)throw new java.io.IOException();
                        flowSets.put(key,set);ownerRules|=set.requiresOwner();
                    }else{
                        SrsAddressSet set=resolver.compileNative(match);if(set==null)throw new java.io.IOException();ruleSets.put(key,set);
                    }
                }catch(java.io.IOException unavailable){throw unsupported("NATIVE_MATCH","Точное условие содержит неподдержанные или недоступные привязки. Исходные настройки сохранены.");}
                conditions.put(new JSONObject().put("type",required.flow?"native_flow":"rule_set").put("value",key));
            }
            if(row.has("presetIds")){
                JSONArray ids=row.optJSONArray("presetIds");
                if(model!=2||resolver==null||!mode.equals("addresses")||ids==null||ids.length()<1||ids.length()>68)throw unsupported("PRESET_ASSET","Нужны новая модель Steer, домены/IP и доверенные готовые списки.");
                Set<String> unique=new HashSet<>();
                for(int at=0;at<ids.length();at++){
                    Object value=ids.get(at);
                    if(!(value instanceof String)||!((String)value).matches("[0-9a-f]{16}")||!unique.add((String)value))throw unsupported("PRESET_ASSET","Некорректная ссылка на готовый список.");
                    String id=(String)value;
                    if(!ruleSets.containsKey(id)){
                        if(packagedCount>=68)throw unsupported("PRESET_ASSET","Слишком много готовых списков.");
                        try{SrsAddressSet set=resolver.load(id);if(set==null)throw new java.io.IOException();ruleSets.put(id,set);packagedCount++;}
                        catch(java.io.IOException unavailable){throw unsupported("PRESET_ASSET","Готовый список недоступен или содержит неподдержанные условия. Профиль сохранён.");}
                    }
                    conditions.put(new JSONObject().put("type","rule_set").put("value",id));
                }
            }
            Set<String> apps=new LinkedHashSet<>();
            for(String term:McrfRuleLines.expand(row.getString("text"),model==2)) {
                String value=term;
                String kind="domain_suffix";
                if(value.contains(",")) {
                    String[] parts=value.split(",",-1);String type=parts[0].trim().toUpperCase(Locale.ROOT);
                    if((parts.length!=2&&parts.length!=3)||(parts.length==3&&!parts[2].trim().equalsIgnoreCase("no-resolve")))throw unsupported("RULE_SYNTAX","Некорректная запись списка.");
                    switch(type){case "DOMAIN":kind="domain";break;case "DOMAIN-SUFFIX":kind="domain_suffix";break;case "IP-CIDR":case "IP-CIDR6":kind="ip_cidr";break;default:throw unsupported("RULE_TYPE","Этот тип записи пока не перенесён.");}
                    value=parts[1].trim();
                    if(type.equals("IP-CIDR6")&&(model!=2||!value.contains(":")))throw unsupported("IPV6_RULE","Нужен новый режим и числовая IPv6-подсеть.");
                }
                String lower=value.toLowerCase(Locale.ROOT);
                if(lower.startsWith("path:"))throw unsupported("APP_CONDITION","На Android нужны имена пакетов, не пути процессов.");
                if(lower.startsWith("app:")||lower.startsWith("pkg:")){
                    if(model!=2)throw unsupported("APP_CONDITION","Условия приложений требуют новой модели Steer.");
                    String pkg=value.substring(4).trim();
                    if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")||!apps.contains(pkg)&&apps.size()>=512)throw unsupported("APP_CONDITION","Нужен корректный ограниченный список пакетов Android.");
                    apps.add(pkg);continue;
                }
                if(value.equals("localhost")||value.equals("localhost.localdomain")||value.equals("broadcasthost")||value.startsWith("ip6-")||value.endsWith(".local"))continue;
                if(value.contains(":")){
                    if(model!=2)throw unsupported("IPV6_MODEL","IPv6-правила требуют нового режима Steer.");
                    try{
                        boolean cidr=value.contains("/");
                        Ipv6Routes.Prefix prefix=Ipv6Routes.parse(cidr?value:value+"/128");
                        byte[] bytes=prefix.addressBytes();boolean local=true;
                        for(int at=0;at<15;at++)if(bytes[at]!=0)local=false;
                        if(!cidr&&local&&(bytes[15]==0||bytes[15]==1))continue;
                        value=prefix.toString();
                    }
                    catch(IllegalArgumentException bad){throw unsupported("IPV6_RULE","Нужна корректная числовая IPv6-подсеть.");}
                    kind="ip_cidr";
                }else if(value.matches("[0-9]+(?:\\.[0-9]+){3}(?:/[0-9]+)?")||kind.equals("ip_cidr")) {
                    try{
                        Ipv4Routes.Prefix prefix=Ipv4Routes.parse(value.contains("/")?value:value+"/32");
                        // Match RouteRules.parse: bare loopback/unspecified literals
                        // are skipped, while explicitly written CIDRs remain rules.
                        if(!value.contains("/")&&(prefix.network==0||(prefix.network>>>24)==127))continue;
                        value=prefix.toString();
                    }catch(IllegalArgumentException e){throw unsupported("IP_RULE","Нужна корректная числовая IPv4-подсеть.");}kind="ip_cidr";
                }else {
                    if(value.contains(":")||value.contains("/")||value.contains(" ")||value.contains("\t"))throw unsupported("RULE_SYNTAX","Некорректная запись домена или IP-подсети.");
                    value=value.replaceFirst("^[*.]+","");if(value.startsWith("||"))value=value.substring(2).replaceFirst("\\^+$","");
                    if(!value.matches("(?=.{1,253}$)[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)*"))throw unsupported("DOMAIN_RULE","Некорректный домен списка MCRF.");
                    try{value=DomainPolicy.name(value);}catch(IllegalArgumentException e){throw unsupported("DOMAIN_RULE","Некорректный или неподдержанный домен.");}
                }
                conditions.put(new JSONObject().put("type",kind).put("value",value));
            }
            if(mode.equals("addresses")&&conditions.length()==0||mode.equals("apps")&&apps.isEmpty()||
                    (mode.equals("in-apps")||mode.equals("except-apps"))&&(conditions.length()==0||apps.isEmpty())||
                    mode.equals("any")&&conditions.length()==0&&apps.isEmpty())throw unsupported("EMPTY_RULE","Для выбранного условия не хватает доменов/IP или приложений.");
            if(invert&&!apps.isEmpty())throw unsupported("INVERT_CONDITION","Инверсия списка с приложениями недопустима.");
            if(row.optJSONObject("dns")!=null&&(!apps.isEmpty()||mode.equals("apps")||mode.equals("in-apps")||mode.equals("except-apps")))
                throw unsupported("RULE_DNS","Отдельный DNS доступен только без условий приложений.");
            JSONObject compiled=new JSONObject().put("type","group").put("entries",conditions).put("invert",invert).put("action",action);
            if(!mode.equals("addresses")&&!apps.isEmpty()){
                compiled.put("mode",mode).put("apps",new JSONArray(apps));ownerRules=true;
            }
            entries.put(compiled);
            if(row.optJSONObject("dns")!=null&&!action.equals("block")){
                String tag="rule-"+i;
                JSONObject dnsRule=new JSONObject().put("entries",new JSONArray(conditions.toString())).put("invert",invert).put("action",action).put("resolverTag",tag);
                DnsRulePolicy eligible=new DnsRulePolicy(new JSONArray().put(dnsRule),ruleSets);
                if(!eligible.requiredTags().isEmpty()){
                    JSONObject normalized=new JSONObject();dns(new JSONObject().put("globalDns",row.getJSONObject("dns")),normalized,ipv6);
                    DnsUpstreamPlan plan=new DnsUpstreamPlan(normalized);
                    String identity=action+"|"+plan.kind+"|"+plan.url+"|"+plan.bootstrap;
                    String shared=dnsTags.get(identity);
                    if(shared==null){
                        if(dnsTags.size()>=32)throw unsupported("DNS_UPSTREAM_LIMIT","Не больше32 различных DNS/маршрутов на один запуск Steer.");
                        shared="dns-"+dnsTags.size();dnsTags.put(identity,shared);dnsUpstreams.put(shared,normalized);
                    }
                    dnsRule.put("resolverTag",shared);dnsRules.put(dnsRule);
                }
            }
        }
        if(dnsRules.length()>0)result.put("dns_rules",dnsRules).put("dns_upstreams",dnsUpstreams);
        if(ownerRules){
            result.put("owner_rules",true);
        }
        result.put("routing_rules",entries);return new SteerConfiguration(result,ruleSets,flowSets);
    }
}
