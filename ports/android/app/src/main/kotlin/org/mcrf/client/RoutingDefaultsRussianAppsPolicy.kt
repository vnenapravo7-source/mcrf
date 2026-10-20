package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** An ordinary visible rule, owned only by this picker. Legacy exclusions are never inferred as ours. */
object RoutingDefaultsRussianAppsPolicy {
    const val RULE_ID = "mcrf-ru-app-direct"
    private const val OWNER = "ru-app-picker"
    private fun owned(rule:JSONObject)=rule.optString("id")==RULE_ID && rule.optString("managedBy")==OWNER
    private fun strings(array:JSONArray?)=if(array==null)emptySet() else (0 until array.length()).map {array.getString(it)}.toSet()
    fun selected(state:JSONObject):Set<String> {
        val rules=state.getJSONArray("rules")
        return (0 until rules.length()).map {rules.getJSONObject(it)}.filter(::owned)
            .flatMap {RouteRules.parse(it.getString("text")).getValue("package_name")}.toSet()
    }
    fun conflicts(state:JSONObject,chosen:Set<String>):Set<String> {
        val result=strings(state.optJSONArray("vpnApps")).intersect(chosen).toMutableSet()
        if(chosen.isNotEmpty()&&state.optString("appMode","all")=="include")result.add("legacy-include-scope")
        val rules=state.getJSONArray("rules")
        repeat(rules.length()) { val rule=rules.getJSONObject(it)
            if(!owned(rule)&&rule.optBoolean("enabled",true)&&rule.optString("target","proxy")!="direct") {
                val match=rule.optJSONObject("nativeMatch")
                if(match!=null&&RemoteRoutingImports.hasAppConditions(match))result.add("rule:"+rule.getString("id"))
                else if(RouteRules.parse(rule.getString("text")).getValue("package_name").any {it in chosen})result.add("rule:"+rule.getString("id"))
            }
        }
        return if(chosen.isEmpty())emptySet() else result
    }
    fun apply(state:JSONObject,shown:Set<String>,chosen:Set<String>,allowVpnConflict:Boolean):JSONObject {
        require(RoutingDefaultsRussianApps.byPackage.keys.containsAll(shown)&&shown.containsAll(chosen))
        val next=JSONObject(state.toString()); val rules=next.getJSONArray("rules")
        require((0 until rules.length()).none {rules.getJSONObject(it).optString("id")==RULE_ID&&!owned(rules.getJSONObject(it))}) {"Идентификатор RU-app занят другим правилом. Оно сохранено без изменений."}
        val packages=(selected(next)-shown)+chosen
        require(allowVpnConflict||conflicts(next,packages).isEmpty()) {"Подтверди приоритет правила RU-app над политиками VPN и другими правилами приложений."}
        val output=JSONArray()
        if(packages.isNotEmpty()) {
            val rule=JSONObject().put("id",RULE_ID).put("managedBy",OWNER).put("name","RU-app · напрямую")
                .put("text",packages.sorted().joinToString("\n") {"app:$it"}).put("mode","apps").put("target","direct").put("enabled",true)
            RouteRules.matches(rule);output.put(rule)
            val forced=next.optJSONArray("vpnApps")?:JSONArray()
            val remaining=(0 until forced.length()).map {forced.getString(it)}.filterNot {it in packages}
            next.put("vpnApps",JSONArray(remaining))
            // Only explicit confirmation may retire Builder's restrictive include scope.
            if(next.optString("appMode","all")=="include")next.put("appMode","all")
        }
        repeat(rules.length()) {val rule=rules.getJSONObject(it);if(!owned(rule))output.put(rule)}
        next.put("rules",output);RoutingStore.validate(next);return next
    }
}
