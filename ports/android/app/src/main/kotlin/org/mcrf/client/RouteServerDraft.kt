package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Shared picker serialization; an untouched selection retains the exact saved policy. */
internal object RouteServerDraft {
    fun write(rule:JSONObject,edited:Boolean,ids:List<String>,automatic:Boolean,interval:Int,tolerance:Int,
        mode:String,failureOnly:Boolean,preferred:String) {
        if(!edited&&rule.optString("target","proxy")=="proxy")return
        rule.remove(RouteServerPlan.KEY);rule.remove(RouteServerPlan.POOL);rule.remove(RouteServerPlan.AUTO)
        if(rule.optString("target","proxy")=="proxy") {
            if(ids.size==1)rule.put(RouteServerPlan.KEY,ids.single())
            else if(ids.isNotEmpty())rule.put(RouteServerPlan.POOL,JSONArray(ids))
            if(automatic&&ids.size>1)rule.put(RouteServerPlan.AUTO,JSONObject().put("interval",interval)
                .put("tolerance",tolerance).put("mode",mode).put("failureOnly",failureOnly)
                .put("preferred",preferred.takeIf {it in ids}?:ids.first()))
        }
        RouteServerPlan.validate(rule)
    }
}
