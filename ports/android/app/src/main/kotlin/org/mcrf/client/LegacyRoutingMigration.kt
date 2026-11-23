package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Versioned, exact legacy-template migration. Never infer intent from Direct alone. */
internal object LegacyRoutingMigration {
    const val KEY="legacyDefaultRouteMigration"
    const val VPN_FALLBACK_KEY="allGroupsVpnFallbackMigration"
    fun apply(state:JSONObject,countryText:String):Boolean {
        if(state.optInt(KEY,0)>=1)return false
        val expected=RouteRules.defaults().put("defaultTarget","direct")
        expected.getJSONArray("rules").put(RouteRules.outsideRussia(countryText))
        // DNS is independent of route intent; allow a valid saved DNS choice.
        state.optJSONObject("globalDns")?.let {expected.put("globalDns",JSONObject(it.toString()))}
        if(state.has("appMode"))expected.put("appMode","all")
        if(state.has(RoutingGroups.KEY)) {RoutingGroups.ensure(expected);RoutingGroups.synchronize(expected)}
        val untouched=equal(state,expected)
        if(untouched)state.put("defaultTarget","proxy")
        state.put(KEY,1)
        return true
    }
    /** Scan each bank and group, keeping IDs, order and whether each rule was enabled. */
    fun replaceCountryRules(root: JSONObject): Boolean {
        val updateFallback = !root.optBoolean(VPN_FALLBACK_KEY)
        var changed = updateFallback
        fun migrate(data: JSONObject) {
            data.optJSONArray("rules")?.let { rules ->
                if (updateFallback) {
                    data.put("defaultTarget", "proxy")
                }
                for (i in 0 until rules.length()) {
                    val rule = rules.getJSONObject(i)
                    if (rule.optString("builtinPreset") != "outside-ru") continue
                    rule.put("builtinPreset", RouteRules.COUNTRY_DIRECT)
                        .put("name", RouteRules.COUNTRY_NAME)
                        .put("target", "direct").put("invert", false)
                    rule.remove(RouteServerPlan.KEY)
                    rule.remove(RouteServerPlan.POOL)
                    rule.remove(RouteServerPlan.AUTO)
                    data.put("defaultTarget", "proxy")
                    changed = true
                }
            }
            data.optJSONObject(RoutingGroups.KEY)?.optJSONArray("groups")?.let { groups ->
                for (i in 0 until groups.length()) migrate(groups.getJSONObject(i).getJSONObject("data"))
            }
        }
        migrate(root)
        root.optJSONObject(ExperienceRouting.KEY)?.let { banks ->
            for (mode in ExperienceMode.entries) banks.optJSONObject(mode.id)?.let(::migrate)
        }
        if (updateFallback) root.put(VPN_FALLBACK_KEY, true)
        return changed
    }

    private fun equal(a:Any?,b:Any?):Boolean=when {
        a is JSONObject&&b is JSONObject->a.keys().asSequence().toSet()==b.keys().asSequence().toSet()&&a.keys().asSequence().all {equal(a.get(it),b.get(it))}
        a is JSONArray&&b is JSONArray->a.length()==b.length()&&(0 until a.length()).all {equal(a.get(it),b.get(it))}
        else->a==b
    }
}
