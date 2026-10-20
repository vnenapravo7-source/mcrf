package org.steer.core;
import org.json.*;
import java.net.IDN;
import java.util.*;

/** First matching rule wins. Domain conditions require a DNS-created fake IP. */
public final class DomainPolicy {
    public static final int PROXY=1,DIRECT=2,BLOCK=3;
    private static final class Condition {
        String type,value;Ipv4Routes.Prefix prefix;Ipv6Routes.Prefix prefix6;SrsAddressSet ruleSet;NativeFlowMatch flowSet;
        boolean matches(String domain,Ipv4Routes.Prefix address,Ipv6Routes.Prefix address6,FlowIdentity original,Set<String> packages,ProxyRequestContext request){
            if(ruleSet!=null||flowSet!=null){
                byte[] numeric=address6!=null?address6.addressBytes():address==null?null:new byte[]{(byte)(address.network>>>24),(byte)(address.network>>>16),(byte)(address.network>>>8),(byte)address.network};
                if(flowSet!=null)return request==null?flowSet.matches(domain,numeric,original,packages):flowSet.matchesProxy(domain,numeric,request);
                return ruleSet.matches(domain,numeric);
            }
            if(prefix!=null)return address!=null&&prefix.contains(address);
            if(prefix6!=null)return address6!=null&&prefix6.contains(address6);
            return domain!=null&&(domain.equals(value)||(type.equals("domain_suffix")&&domain.endsWith("."+value)));
        }
    }
    private static final class Rule {
        List<Condition> conditions=new ArrayList<>();Set<String> apps=new HashSet<>();
        String mode="addresses";int action;boolean invert;
    }
    private final List<Rule> rules=new ArrayList<>();
    private final int fallback;
    private final boolean ipv6;
    private final boolean requiresOwner;
    private final boolean requiresFlow;
    public static String name(String text) {
        String value=text.endsWith(".")?text.substring(0,text.length()-1):text;
        value=IDN.toASCII(value,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if(value.isEmpty()||value.length()>253)throw new IllegalArgumentException("Invalid domain");
        for(String part:value.split("\\.",-1))if(part.isEmpty()||part.length()>63)throw new IllegalArgumentException("Invalid domain");
        return value;
    }
    private static int actionCode(String action) {
        switch(action){case "proxy":return PROXY;case "direct":return DIRECT;case "block":return BLOCK;default:throw new IllegalArgumentException("Invalid rule action");}
    }
    private static void fields(JSONObject row,String... allowed) {
        Set<String> known=new HashSet<>(Arrays.asList(allowed));
        for(Iterator<String> keys=row.keys();keys.hasNext();)if(!known.contains(keys.next()))throw new IllegalArgumentException("Unknown routing entry field");
    }
    private static Condition condition(JSONObject row,boolean ipv6,Map<String,SrsAddressSet> sets,Map<String,NativeFlowMatch> flows) throws JSONException {
        if(!(row.opt("type") instanceof String)||!(row.opt("value") instanceof String))throw new IllegalArgumentException("String routing condition required");
        Condition c=new Condition();c.type=row.getString("type");c.value=row.getString("value");
        if(c.type.equals("ip_cidr")){
            if(ipv6&&c.value.indexOf(':')>=0)c.prefix6=Ipv6Routes.parse(c.value);
            else c.prefix=Ipv4Routes.parse(c.value);
        }
        else if(c.type.equals("domain")||c.type.equals("domain_suffix"))c.value=name(c.value);
        else if(c.type.equals("rule_set")){
            c.ruleSet=sets.get(c.value);if(c.ruleSet==null)throw new IllegalArgumentException("Unbound rule-set condition");
        }
        else if(c.type.equals("native_flow")){
            c.flowSet=flows.get(c.value);if(c.flowSet==null)throw new IllegalArgumentException("Unbound original-flow condition");
        }
        else throw new IllegalArgumentException("Unsupported rule type");
        return c;
    }
    public DomainPolicy(JSONArray rows) throws JSONException {this(rows,"proxy");}
    public DomainPolicy(JSONArray rows,String defaultAction) throws JSONException {
        this(rows,defaultAction,false);
    }
    /** Preparation for the dual-stack ABI. Existing native callers remain strict IPv4.
     * This flag is never inferred from a profile or used to bypass module admission. */
    public DomainPolicy(JSONArray rows,String defaultAction,boolean ipv6) throws JSONException {
        this(rows,defaultAction,ipv6,false);
    }
    /** Source-only owner-aware model. Caller must separately prove the UID of
     * the ORIGINAL captured socket. No configured/default/package guess may
     * stand in for a missing owner. Existing constructors refuse these fields. */
    public DomainPolicy(JSONArray rows,String defaultAction,boolean ipv6,boolean ownerRules) throws JSONException {
        this(rows,defaultAction,ipv6,ownerRules,Collections.emptyMap());
    }
    public DomainPolicy(JSONArray rows,String defaultAction,boolean ipv6,boolean ownerRules,Map<String,SrsAddressSet> boundSets) throws JSONException {
        this(rows,defaultAction,ipv6,ownerRules,boundSets,Collections.emptyMap());
    }
    /** SOURCE ONLY original-flow bindings. Public module admission remains OFF;
     * immutable predicates cannot substitute for actual callback metadata. */
    public DomainPolicy(JSONArray rows,String defaultAction,boolean ipv6,boolean ownerRules,Map<String,SrsAddressSet> boundSets,Map<String,NativeFlowMatch> boundFlows) throws JSONException {
        // 68 signed catalogue sets plus at most one native-address expression
        // per1024 captured rows; all materialization shares the worker budget.
        if(boundSets==null||boundSets.size()>1092||boundSets.containsValue(null))throw new IllegalArgumentException("Bounded materialized rule-sets required");
        if(boundFlows==null||boundFlows.size()>1024||boundSets.size()+boundFlows.size()>1092||boundFlows.containsValue(null)||!ownerRules&&!boundFlows.isEmpty())
            throw new IllegalArgumentException("Bounded model2 original-flow predicates required");
        Map<String,SrsAddressSet> sets=new HashMap<>(boundSets);
        Map<String,NativeFlowMatch> flows=new HashMap<>(boundFlows);
        this.ipv6=ipv6;
        fallback=actionCode(defaultAction);
        if(rows.length()>4096)throw new IllegalArgumentException("At most 4096 routing entries");
        int count=0;boolean needsOwner=false,needsFlow=false;
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);Rule r=new Rule();r.action=actionCode(row.getString("action"));
            if(row.getString("type").equals("group")) {
                if(ownerRules)fields(row,"type","entries","invert","action","mode","apps");
                else fields(row,"type","entries","invert","action");
                if(row.has("mode")){
                    r.mode=row.getString("mode");
                    if(!Arrays.asList("addresses","apps","in-apps","except-apps","any").contains(r.mode))throw new IllegalArgumentException("Unknown application condition mode");
                }
                if(row.has("apps")){
                    JSONArray apps=row.getJSONArray("apps");if(apps.length()>512)throw new IllegalArgumentException("Application condition capacity");
                    for(int at=0;at<apps.length();at++){
                        String pkg=apps.getString(at);
                        if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")||!r.apps.add(pkg)||++count>4096)
                            throw new IllegalArgumentException("Invalid/duplicate application condition");
                    }
                }
                if(row.has("invert")&&!(row.get("invert") instanceof Boolean))throw new IllegalArgumentException("Boolean invert required");
                r.invert=row.optBoolean("invert",false);JSONArray entries=row.getJSONArray("entries");
                for(int j=0;j<entries.length();j++) {
                    if(++count>4096)throw new IllegalArgumentException("At most 4096 routing conditions");
                    JSONObject entry=entries.getJSONObject(j);fields(entry,"type","value");r.conditions.add(condition(entry,ipv6,sets,flows));
                }
                if(r.mode.equals("addresses")&&r.conditions.isEmpty()||r.mode.equals("apps")&&r.apps.isEmpty()||
                        (r.mode.equals("in-apps")||r.mode.equals("except-apps"))&&(r.conditions.isEmpty()||r.apps.isEmpty())||
                        r.mode.equals("any")&&r.conditions.isEmpty()&&r.apps.isEmpty())throw new IllegalArgumentException("Empty condition for routing mode");
                if(r.invert&&(!r.mode.equals("addresses")||!r.apps.isEmpty()))throw new IllegalArgumentException("Only address-only condition can be inverted");
                needsOwner|=!r.mode.equals("addresses")&&!r.apps.isEmpty();
            } else {
                fields(row,"type","value","action");
                if(++count>4096)throw new IllegalArgumentException("At most 4096 routing conditions");
                r.conditions.add(condition(row,ipv6,sets,flows));
            }
            for(Condition condition:r.conditions)if(condition.flowSet!=null){needsFlow|=condition.flowSet.requiresFlow();needsOwner|=condition.flowSet.requiresOwner();}
            rules.add(r);
        }
        requiresOwner=needsOwner;
        requiresFlow=needsFlow;
    }
    public int action(String domain,long ip) {
        if(requiresOwner||requiresFlow)return BLOCK;
        Ipv4Routes.Prefix address=new Ipv4Routes.Prefix(ip&0xffffffffL,32);
        return action(domain,address,null,null,null);
    }
    public boolean ipv6Enabled(){return ipv6;}
    public boolean requiresOwner(){return requiresOwner;}
    public boolean requiresFlow(){return requiresFlow;}
    /** DNS can retain domain/real-address metadata until the flow owner is
     * known. This is NOT a traffic routing decision or an app allow-list. */
    public int dnsAction(String domain,long ip){return requiresOwner||requiresFlow?PROXY:action(domain,ip);}
    public int dnsAction6(String domain,byte[] ip){
        if(!ipv6)throw new IllegalStateException("IPv6 policy requires explicit dual-stack admission");
        return requiresOwner||requiresFlow?PROXY:action6(domain,ip);
    }
    public int action6(String domain,byte[] ip){
        if(!ipv6)throw new IllegalStateException("IPv6 policy requires explicit dual-stack admission");
        if(requiresOwner||requiresFlow)return BLOCK;
        return action(domain,null,new Ipv6Routes.Prefix(ip,128),null,null);
    }
    public int actionForOwner(String domain,long ip,Set<String> packages){
        if(requiresFlow)return BLOCK; // a package set alone is not a tuple
        Set<String> owner=identity(packages);if(requiresOwner&&owner==null)return BLOCK;
        return action(domain,new Ipv4Routes.Prefix(ip&0xffffffffL,32),null,owner,null);
    }
    public int action6ForOwner(String domain,byte[] ip,Set<String> packages){
        if(!ipv6)throw new IllegalStateException("IPv6 policy requires explicit dual-stack admission");
        if(requiresFlow)return BLOCK;
        Set<String> owner=identity(packages);if(requiresOwner&&owner==null)return BLOCK;
        return action(domain,null,new Ipv6Routes.Prefix(ip,128),owner,null);
    }
    public int actionForFlow(String domain,long realAddress,FlowIdentity original,Set<String> packages){
        if(original==null||original.family!=4||realAddress<0||realAddress>0xffffffffL)return BLOCK;
        Set<String> owner=identity(packages);if(requiresOwner&&owner==null)return BLOCK;
        return action(domain,new Ipv4Routes.Prefix(realAddress&0xffffffffL,32),null,owner,original);
    }
    public int action6ForFlow(String domain,byte[] realAddress,FlowIdentity original,Set<String> packages){
        if(!ipv6)throw new IllegalStateException("IPv6 policy requires explicit dual-stack admission");
        if(original==null||original.family!=6||realAddress==null||realAddress.length!=16||Ipv6Routes.mappedIpv4(realAddress))return BLOCK;
        Set<String> owner=identity(packages);if(requiresOwner&&owner==null)return BLOCK;
        return action(domain,null,new Ipv6Routes.Prefix(realAddress,128),owner,original);
    }
    private static Set<String> identity(Set<String> packages){
        if(packages==null||packages.isEmpty()||packages.size()>512)return null;
        Set<String> result=new HashSet<>(packages);
        for(String value:result)if(value==null||value.length()>255||!value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*"))return null;
        return result;
    }
    /** SOURCE ONLY: caller must validate the actual host socket/request lease
     * before invoking this. Context construction alone is not authorization. */
    public int actionForProxy(String domain,byte[] realAddress,ProxyRequestContext request){
        if(request==null||realAddress==null||(realAddress.length!=4&&realAddress.length!=16)||
                realAddress.length==16&&(!ipv6||Ipv6Routes.mappedIpv4(realAddress)))return BLOCK;
        byte[] target=request.requested().address();if(target!=null&&target.length!=realAddress.length)return BLOCK;
        Set<String> owner=identity(request.packages());if(owner==null)return BLOCK;
        if(realAddress.length==16)return action(domain,null,new Ipv6Routes.Prefix(realAddress,128),owner,null,request);
        long ip=0;for(byte b:realAddress)ip=(ip<<8)|(b&255);
        return action(domain,new Ipv4Routes.Prefix(ip,32),null,owner,null,request);
    }
    private int action(String domain,Ipv4Routes.Prefix address,Ipv6Routes.Prefix address6,Set<String> packages,FlowIdentity original){
        return action(domain,address,address6,packages,original,null);
    }
    private int action(String domain,Ipv4Routes.Prefix address,Ipv6Routes.Prefix address6,Set<String> packages,FlowIdentity original,ProxyRequestContext request){
        for(Rule r:rules) {
            boolean addressMatch=false;for(Condition condition:r.conditions)if(condition.matches(domain,address,address6,original,packages,request)){addressMatch=true;break;}
            boolean appMatch=packages!=null&&!Collections.disjoint(r.apps,packages),matched;
            switch(r.mode){
                case "apps":matched=appMatch;break;
                case "in-apps":matched=addressMatch&&appMatch;break;
                case "except-apps":matched=addressMatch&&!appMatch;break;
                case "any":matched=addressMatch||appMatch;break;
                default:matched=addressMatch;
            }
            if(matched!=r.invert)return r.action;
        }
        return fallback;
    }
}
