package org.steer.core;

import org.json.*;
import java.util.*;

/** Ordered DNS selection model for materialized address rules. This does NOT
 * open a resolver or enable per-rule DNS in current module admission. A future
 * owner must bind EVERY referenced tag to an owned authenticated/protected
 * upstream before accepting the configuration. No missing-tag/global fallback.
 * Matching mirrors the host's listDnsMatch: domain union; inverted mixed IP
 * groups have no DNS effect, IP-only lists do not pretend to match names, and
 * only explicit BOTH-family /0 coverage represents every DNS name. */
public final class DnsRulePolicy {
    public static final class Choice {
        public final String resolverTag;
        public final int action;
        private Choice(String tag,int action){resolverTag=tag;this.action=action;}
        @Override public String toString(){return "DnsChoice(redacted)";}
    }
    private static final class Name {
        final String domain;final boolean suffix;final SrsAddressSet set;
        Name(String domain,boolean suffix){this.domain=domain;this.suffix=suffix;this.set=null;}
        Name(SrsAddressSet set){this.domain=null;this.suffix=false;this.set=set;}
        boolean matches(String value){return set!=null?set.matches(value,null):domain.equals(value)||(suffix&&value.endsWith("."+domain));}
    }
    private static final class Rule {
        final List<Name> names=new ArrayList<>();
        boolean invert,eligible,all;Choice choice;
    }
    private final List<Rule> rules=new ArrayList<>();
    private final Set<String> tags;
    private final Map<String,Integer> bindings;
    private static void fields(JSONObject value,String... allowed){
        Set<String> known=new HashSet<>(Arrays.asList(allowed));
        for(Iterator<String> keys=value.keys();keys.hasNext();)if(!known.contains(keys.next()))throw new IllegalArgumentException("Unknown DNS rule field");
    }
    public DnsRulePolicy(JSONArray input)throws JSONException {
        this(input,Collections.emptyMap());
    }
    public DnsRulePolicy(JSONArray input,Map<String,SrsAddressSet> boundSets)throws JSONException {
        if(boundSets==null||boundSets.size()>1092||boundSets.containsValue(null))throw new IllegalArgumentException("Bounded DNS rule-sets required");
        Map<String,SrsAddressSet> sets=new HashMap<>(boundSets);
        if(input==null||input.length()>1024)throw new IllegalArgumentException("Bounded DNS rules required");
        int count=0;Set<String> referenced=new LinkedHashSet<>();Map<String,Integer> actions=new LinkedHashMap<>();
        for(int i=0;i<input.length();i++){
            JSONObject row=input.getJSONObject(i);fields(row,"entries","invert","action","resolverTag");
            if(row.has("invert")&&!(row.opt("invert") instanceof Boolean))throw new IllegalArgumentException("Boolean DNS inversion required");
            if(!(row.opt("resolverTag") instanceof String)||!row.getString("resolverTag").matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("Owned DNS tag required");
            Rule rule=new Rule();rule.invert=row.optBoolean("invert",false);
            String action=row.getString("action");int code;
            if(action.equals("proxy"))code=DomainPolicy.PROXY;else if(action.equals("direct"))code=DomainPolicy.DIRECT;
            else if(action.equals("block"))code=DomainPolicy.BLOCK;else throw new IllegalArgumentException("Unknown DNS path");
            JSONArray conditions=row.getJSONArray("entries");boolean ip=false,all4=false,all6=false;
            if(conditions.length()==0)throw new IllegalArgumentException("Explicit DNS conditions required");
            for(int at=0;at<conditions.length();at++){
                if(++count>4096)throw new IllegalArgumentException("DNS condition capacity");
                JSONObject entry=conditions.getJSONObject(at);fields(entry,"type","value");
                if(!(entry.opt("type") instanceof String)||!(entry.opt("value") instanceof String))throw new IllegalArgumentException("String DNS condition required");
                String type=entry.getString("type"),value=entry.getString("value");
                if(type.equals("domain")||type.equals("domain_suffix"))rule.names.add(new Name(DomainPolicy.name(value),type.equals("domain_suffix")));
                else if(type.equals("ip_cidr")){
                    ip=true;if(value.indexOf(':')>=0)all6|=Ipv6Routes.parse(value).bits==0;
                    else all4|=Ipv4Routes.parse(value).bits==0;
                }else if(type.equals("rule_set")){
                    SrsAddressSet set=sets.get(value);if(set==null)throw new IllegalArgumentException("Unbound DNS rule-set");
                    // Host listDnsMatch omits ANY preset carrying IP ranges,
                    // even if it also has domains. Never treat missing DNS-time
                    // IP metadata as false and invert it into a name match.
                    ip|=set.hasIps();if(set.hasDomains()&&!set.hasIps())rule.names.add(new Name(set));
                }else throw new IllegalArgumentException("Unsupported DNS condition");
            }
            rule.all=rule.names.isEmpty()&&!rule.invert&&all4&&all6;
            rule.eligible=code!=DomainPolicy.BLOCK&&!(rule.invert&&ip)&&(!rule.names.isEmpty()||rule.all);
            rule.choice=new Choice(row.getString("resolverTag"),code);
            if(rule.eligible){
                String tag=rule.choice.resolverTag;Integer previous=actions.put(tag,code);
                if(previous!=null&&previous!=code)throw new IllegalArgumentException("DNS tag has conflicting detours");
                referenced.add(tag);if(referenced.size()>32)throw new IllegalArgumentException("DNS upstream capacity");
            }
            rules.add(rule);
        }
        tags=Collections.unmodifiableSet(referenced);
        bindings=Collections.unmodifiableMap(actions);
    }
    public Set<String> requiredTags(){return tags;}
    public Map<String,Integer> bindings(){return bindings;}
    /** Null means the configured GLOBAL DNS owner, not platform/ISP DNS. */
    public Choice select(String questionName){
        String name=DomainPolicy.name(questionName);
        for(Rule rule:rules){
            if(!rule.eligible)continue;
            if(rule.all)return rule.choice;
            boolean matched=false;for(Name condition:rule.names)if(condition.matches(name)){matched=true;break;}
            if(matched!=rule.invert)return rule.choice;
        }
        return null;
    }
    public void requireBound(Set<String> boundTags){
        if(boundTags==null||!boundTags.containsAll(tags))throw new IllegalArgumentException("Unbound per-rule DNS upstream");
    }
}
