package org.mcrf.steerprep;

import org.json.*;
import java.net.URI;
import java.util.*;

/** SOURCE ONLY: deterministic Geo ordering/reference plan, NOT compatibility admission.
 * Every emitted nativeMatch still needs full worker-side semantic validation.
 * Does not touch storage/network, mutate routing, or enable a module capability. */
public final class SteerGeoProjection {
    private SteerGeoProjection() {}
    public static final class Plan {
        private final String rows, selectors;
        public final Set<Integer> neededSelectors;
        private Plan(JSONArray rows, JSONArray selectors, Set<Integer> needed) {
            this.rows=rows.toString();this.selectors=selectors.toString();
            neededSelectors=Collections.unmodifiableSet(new LinkedHashSet<>(needed));
        }
        public JSONArray rules() throws JSONException { return new JSONArray(rows); }
        public JSONObject selector(int index) throws JSONException { return new JSONArray(selectors).getJSONObject(index); }
    }
    private static void require(boolean ok,String message) {
        if(!ok)throw new IllegalArgumentException(message);
    }
    private static void fields(JSONObject row,String... names) {
        Set<String> allowed=new HashSet<>(Arrays.asList(names));
        for(Iterator<String> it=row.keys();it.hasNext();)require(allowed.contains(it.next()),"Unknown Geo field");
    }
    private static String text(JSONObject row,String key) throws JSONException {
        require(row.opt(key) instanceof String,"Geo string required");return row.getString(key);
    }
    private static boolean flag(JSONObject row,String key,boolean fallback) throws JSONException {
        if(!row.has(key))return fallback;
        require(row.opt(key) instanceof Boolean,"Geo boolean required");return row.getBoolean(key);
    }
    private static void target(JSONObject row) throws JSONException {
        require(Arrays.asList("proxy","direct","block").contains(text(row,"target")),"Invalid Geo target");
    }
    private static void source(JSONObject geo) throws JSONException {
        require(Arrays.asList("Loyalsoldier","v2fly","custom").contains(text(geo,"source")),"Invalid Geo source");
        for(String key:Arrays.asList("geoipUrl","geositeUrl"))try {
            String raw=text(geo,key);require(raw.length()<=4096,"Geo URL bound");URI uri=new URI(raw);
            require("https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getRawUserInfo()==null&&uri.getRawFragment()==null,"HTTPS Geo source required");
        }catch(java.net.URISyntaxException bad){throw new IllegalArgumentException("Invalid Geo URL",bad);}
    }
    private static int reference(Object value,int count,boolean geoOnly) {
        require(value instanceof String,"Geo reference string required");String tag=(String)value;
        if(!geoOnly&&!tag.startsWith("mcrf-geo-"))return -1;
        require(tag.matches("mcrf-geo-(?:0|[1-9][0-9]?)"),"Unbound Geo reference");
        int id=Integer.parseInt(tag.substring(9));require(id<count,"Geo reference outside selector snapshot");return id;
    }
    // Used only to find dependencies. Preserving a key does NOT mean supporting
    // its matching semantics. The adapter/worker must reject unknown conditions.
    private static void references(JSONObject match,int count,Set<Integer> found,int depth,int[] nodes,boolean geoOnly) throws JSONException {
        require(depth<=16&&++nodes[0]<=4096,"Geo reference tree bound");
        if(match.has("rule_set")){
            Object raw=match.get("rule_set");JSONArray tags=raw instanceof String?new JSONArray().put(raw):match.optJSONArray("rule_set");
            require(tags!=null&&tags.length()>0&&tags.length()<=4096&&nodes[1]<=4096-tags.length(),"Geo reference list bound");nodes[1]+=tags.length();
            for(int i=0;i<tags.length();i++){int id=reference(tags.get(i),count,geoOnly);if(id>=0)found.add(id);}
        }
        if(match.has("rules")){
            require("logical".equals(match.opt("type")),"Logical Geo reference container required");
            JSONArray rows=match.optJSONArray("rules");require(rows!=null&&rows.length()>0&&rows.length()<=256,"Geo nested rules required");
            for(int i=0;i<rows.length();i++)references(rows.getJSONObject(i),count,found,depth+1,nodes,geoOnly);
        }
    }
    private static JSONObject row(JSONObject value,Set<String> ids) throws JSONException {
        JSONObject copy=new JSONObject(value.toString());String id=text(copy,"id");
        require(id.matches("[a-zA-Z0-9_-]{1,64}")&&ids.add(id),"Duplicate/invalid effective Geo row identity");
        flag(copy,"enabled",true);flag(copy,"invert",false);target(copy);return copy;
    }
    public static Plan plan(JSONObject original) throws JSONException {
        // Take one private snapshot before any traversal. Never retain caller JSON.
        JSONObject state=new JSONObject(original.toString());JSONArray user=state.getJSONArray("rules");
        require(user.length()<=1024,"Geo user rule bound");JSONObject geo=state.optJSONObject("geoData");
        if(geo==null)return new Plan(user,new JSONArray(),Collections.emptySet());
        fields(geo,"source","geoipUrl","geositeUrl","selectors","providerRules");source(geo);
        JSONArray selectors=geo.getJSONArray("selectors"),provider=geo.optJSONArray("providerRules");
        require(selectors.length()<=64,"Geo selector bound");
        require(!geo.has("providerRules")||provider!=null,"Geo provider rules must be an array");
        if(provider==null)provider=new JSONArray();require(provider.length()<=1024,"Geo provider rule bound");
        for(int i=0;i<selectors.length();i++){
            JSONObject s=selectors.getJSONObject(i);fields(s,"kind","code","target","managedRule","bound");
            require(Arrays.asList("geoip","geosite").contains(text(s,"kind")),"Invalid Geo selector kind");
            require(text(s,"code").matches("[a-zA-Z0-9_-]{1,64}"),"Invalid Geo selector code");
            target(s);flag(s,"managedRule",false);flag(s,"bound",false);
        }
        Set<Integer> userRefs=new LinkedHashSet<>(),allRefs=new LinkedHashSet<>(),providerRefs=new HashSet<>();
        Set<String> ids=new HashSet<>();JSONArray checkedUser=new JSONArray(),checkedProvider=new JSONArray();int[] nodes={0,0};
        for(int i=0;i<user.length();i++){
            JSONObject r=row(user.getJSONObject(i),ids);checkedUser.put(r);
            if(flag(r,"enabled",true)&&r.has("nativeMatch"))references(r.getJSONObject("nativeMatch"),selectors.length(),userRefs,0,nodes,false);
        }
        for(int i=0;i<provider.length();i++){
            JSONObject r=row(provider.getJSONObject(i),ids);checkedProvider.put(r);
            JSONObject match=r.getJSONObject("nativeMatch");
            Set<Integer> refs=new LinkedHashSet<>();references(match,selectors.length(),refs,0,nodes,true);
            providerRefs.addAll(refs);
            if(flag(r,"enabled",true))allRefs.addAll(refs);
        }
        allRefs.addAll(userRefs);Set<Integer> needed=new LinkedHashSet<>();JSONArray added=new JSONArray();
        for(int i=0;i<selectors.length();i++){
            JSONObject s=selectors.getJSONObject(i);boolean bound=flag(s,"bound",false),managed=flag(s,"managedRule",false);
            require(!bound||providerRefs.contains(i),"Bound Geo selector has no provider row");
            if(!managed||allRefs.contains(i))needed.add(i);
            if(!bound&&!managed){
                String id="mcrf-geo-selector-"+i;require(ids.add(id),"Geo synthesized row identity collision");
                added.put(new JSONObject().put("id",id).put("name",s.getString("kind")+":"+s.getString("code"))
                    .put("text","").put("mode","addresses").put("target",s.getString("target")).put("enabled",true)
                    .put("nativeMatch",new JSONObject().put("rule_set",new JSONArray().put("mcrf-geo-"+i))));
            }
        }
        require(needed.containsAll(allRefs),"Enabled Geo reference has no active selector");
        JSONArray ordered=new JSONArray();boolean inserted=false;
        for(int i=0;i<checkedUser.length();i++){
            JSONObject r=checkedUser.getJSONObject(i);
            if(!inserted&&"outside-ru".equals(r.optString("builtinPreset"))){
                for(int j=0;j<checkedProvider.length();j++)ordered.put(checkedProvider.getJSONObject(j));inserted=true;
            }
            ordered.put(r);
        }
        if(!inserted)for(int j=0;j<checkedProvider.length();j++)ordered.put(checkedProvider.getJSONObject(j));
        JSONArray effective=new JSONArray();inserted=false;
        for(int i=0;i<ordered.length();i++){
            JSONObject r=ordered.getJSONObject(i);JSONObject match=r.optJSONObject("nativeMatch");
            boolean inverted=flag(r,"invert",false)||(match!=null&&flag(match,"invert",false));
            // Like GeoDataConfig.attach, only compiled/enabled inversions form
            // an insertion boundary; a disabled fallback is not a live route.
            if(!inserted&&flag(r,"enabled",true)&&inverted){
                for(int j=0;j<added.length();j++)effective.put(added.getJSONObject(j));inserted=true;
            }
            effective.put(r);
        }
        if(!inserted)for(int j=0;j<added.length();j++)effective.put(added.getJSONObject(j));
        require(effective.length()<=1024,"Combined effective Geo rule bound");
        return new Plan(effective,selectors,needed);
    }
}
