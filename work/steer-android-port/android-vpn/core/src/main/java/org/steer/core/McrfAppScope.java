package org.steer.core;

import org.json.*;
import java.util.*;

/** Model2 scope. The host is an explicit control participant, not an extra
 * user-selected proxy app. Android shared-UID capture remains UID-granular. */
public final class McrfAppScope {
    public final List<String> excluded,included,forced;
    public final String controlApp;
    public final boolean controlOnlyHost;
    public McrfAppScope(JSONObject input)throws JSONException{
        Object mode=input.has("appMode")?input.get("appMode"):"all";
        if(!"all".equals(mode)&&!"include".equals(mode))throw new IllegalArgumentException("Unknown application scope");
        List<String> omitted=packages(input,"excludedApps",512),selected=packages(input,"vpnApps",256);
        Set<String> overlap=new HashSet<>(omitted);overlap.retainAll(selected);
        if(!overlap.isEmpty())throw new IllegalArgumentException("Forced and excluded applications overlap");
        forced=selected;
        if("include".equals(mode)){
            Object host=input.opt("hostPackage");
            if(!(host instanceof String)||!valid((String)host)||selected.isEmpty()||omitted.contains(host))
                throw new IllegalArgumentException("Include scope needs selected apps and a nonexcluded host control package");
            controlApp=(String)host;controlOnlyHost=!selected.contains(controlApp);
            List<String> captured=new ArrayList<>(selected);if(controlOnlyHost)captured.add(controlApp);
            included=Collections.unmodifiableList(captured);excluded=Collections.emptyList();
        }else{
            if(input.has("hostPackage"))throw new IllegalArgumentException("Unexpected host control scope");
            controlApp=null;controlOnlyHost=false;included=Collections.emptyList();excluded=omitted;
        }
    }
    public static boolean valid(String value){return value!=null&&value.length()<=255&&value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");}
    private static List<String> packages(JSONObject input,String key,int limit)throws JSONException{
        if(!input.has(key))return Collections.emptyList();
        JSONArray values=input.optJSONArray(key);
        if(values==null||values.length()>limit)throw new IllegalArgumentException("Application scope capacity/type");
        List<String> result=new ArrayList<>();Set<String> unique=new HashSet<>();
        for(int at=0;at<values.length();at++){
            Object value=values.get(at);
            if(!(value instanceof String)||!valid((String)value)||!unique.add((String)value))
                throw new IllegalArgumentException("Invalid or duplicate application scope");
            result.add((String)value);
        }
        return Collections.unmodifiableList(result);
    }
}
