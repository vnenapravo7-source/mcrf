package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Versioned, exact legacy-template migration. Never infer intent from Direct alone. */
internal object LegacyRoutingMigration {
    const val KEY="legacyDefaultRouteMigration"
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
    private fun equal(a:Any?,b:Any?):Boolean=when {
        a is JSONObject&&b is JSONObject->a.keys().asSequence().toSet()==b.keys().asSequence().toSet()&&a.keys().asSequence().all {equal(a.get(it),b.get(it))}
        a is JSONArray&&b is JSONArray->a.length()==b.length()&&(0 until a.length()).all {equal(a.get(it),b.get(it))}
        else->a==b
    }
}
