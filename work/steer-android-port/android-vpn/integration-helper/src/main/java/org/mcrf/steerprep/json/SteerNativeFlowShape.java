package org.mcrf.steerprep.json;

import org.json.*;
import java.util.*;

/** Pure feature-demand inspection shared by main and worker. NOT capability
 * advertisement, semantic compilation, original-tuple or Android UID proof. */
public final class SteerNativeFlowShape {
    private SteerNativeFlowShape() {}
    public static final class Requirements {
        public final boolean flow,owner;
        private Requirements(boolean flow,boolean owner){this.flow=flow;this.owner=owner;}
    }
    public static Requirements inspect(JSONObject root){return row(root,0,new int[]{0});}
    private static IllegalArgumentException bad(){return new IllegalArgumentException("NATIVE_MATCH_SHAPE");}
    private static Requirements row(JSONObject value,int depth,int[] count){
        if(value==null||depth>16||++count[0]>4096)throw bad();
        if(value.has("invert")&&!(value.opt("invert") instanceof Boolean))throw bad();
        if(value.has("type")&&!(value.opt("type") instanceof String))throw bad();
        String type=value.optString("type","default");
        Set<String> known;
        if(type.equals("logical"))known=new HashSet<>(Arrays.asList("type","mode","rules","invert"));
        else if(type.equals("default"))known=new HashSet<>(Arrays.asList("type","invert","domain","domain_suffix","domain_keyword","domain_regex","ip_cidr","rule_set",
            "source_ip_cidr","source_port","source_port_range","port","port_range","network","package_name"));
        else throw bad();
        for(Iterator<String> it=value.keys();it.hasNext();)if(!known.contains(it.next()))throw bad();
        if(!type.equals("logical")){
            boolean owner=value.has("package_name");
            boolean flow=owner||value.has("source_ip_cidr")||value.has("source_port")||value.has("source_port_range")||
                value.has("port")||value.has("port_range")||value.has("network");
            return new Requirements(flow,owner);
        }
        if(!"and".equals(value.opt("mode"))&&!"or".equals(value.opt("mode")))throw bad();
        JSONArray rows=value.optJSONArray("rules");if(rows==null||rows.length()<1||rows.length()>256)throw bad();
        boolean flow=false,owner=false;
        for(int i=0;i<rows.length();i++){
            Requirements child=row(rows.optJSONObject(i),depth+1,count);flow|=child.flow;owner|=child.owner;
        }
        return new Requirements(flow,owner);
    }
}
