package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Explicitly confirmed global-policy repair only; never removes individual routing lists. */
object RoutingAppPolicies {
    internal data class SystemScope(val direct:Set<String>,val ru:Set<String>,val forced:List<String>,val projected:Set<String>)

    private fun packagesOnly(rule:JSONObject):List<String>?{
        if(rule.has("nativeMatch")||rule.optBoolean("invert")||rule.has("dns")||
            PresetRules.ids(rule).isNotEmpty()||
            rule.optString("mode","any") !in setOf("apps","any"))return null
        val parsed=RouteRules.parse(rule.getString("text"))
        if(parsed.any { (kind,values)->kind!="package_name"&&values.isNotEmpty() })return null
        return parsed.getValue("package_name").takeIf {it.isNotEmpty()}
    }

    private fun values(array:JSONArray?)=if(array==null)emptyList() else (0 until array.length()).map(array::getString)

    internal fun systemScope(state:JSONObject):SystemScope {
        val ru=RoutingDefaultsRussianAppsPolicy.systemPackages(state)
        val forced=values(state.optJSONArray("vpnApps")).filterNot(ru::contains)
        val direct=(ru+values(state.optJSONArray("excludedApps"))).toCollection(linkedSetOf())
        val claimed=(direct+forced).toMutableSet()
        val conditional=mutableSetOf<String>()
        val projected=linkedSetOf<String>()
        val rules=state.getJSONArray("rules")
        repeat(rules.length()){index->
            val rule=rules.getJSONObject(index)
            if(!rule.optBoolean("enabled",true))return@repeat
            val target=rule.optString("target","proxy")
            val apps=packagesOnly(rule)
            if(apps==null){
                if(target!="direct"&&rule.optString("mode","any") in setOf("in-apps","any")&&!rule.has("nativeMatch")){
                    conditional.addAll(RouteRules.parse(rule.getString("text")).getValue("package_name"))
                }
                return@repeat
            }
            val first=apps.filter(claimed::add)
            if(target=="direct"){
                if(RoutingDefaultsRussianAppsPolicy.systemRule(rule)||state.optString("appMode","all")!="include"){
                    // A higher app+address match still needs the engine for this UID.
                    direct.addAll(first.filterNot(conditional::contains))
                    if(first.none(conditional::contains))projected.add(rule.getString("id"))
                }
            }
        }
        return SystemScope(direct,ru,forced,projected)
    }

    /** Only captured app traffic needs engine-level routing and DNS rules. */
    internal fun runtimeState(state:JSONObject,vpn:Boolean,scope:SystemScope=systemScope(state)):JSONObject {
        val result=JSONObject(state.toString())
        val rules=result.getJSONArray("rules")
        val active=(0 until rules.length()).map(rules::getJSONObject).filterNot {
            RoutingDefaultsRussianAppsPolicy.systemRule(it)||vpn&&it.getString("id") in scope.projected
        }
        result.put("rules",JSONArray(active))
        if(vpn)result.put("excludedApps",JSONArray(scope.direct)).put("vpnApps",JSONArray(scope.forced))
        return result
    }

    fun clearGlobal(state:JSONObject) {
        state.put("excludedApps",JSONArray()).put("vpnApps",JSONArray()).put("appMode","all")
    }
}
