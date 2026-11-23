package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Only the selected bank reaches runtime consumers. Old routes migrate to Expert. */
internal object ExperienceRouting {
    const val KEY = "experienceRouting"
    private val fields = setOf("rules", "defaultTarget", "excludedApps", "vpnApps", "appMode",
        "geoData", "remoteRouting", RoutingGroups.KEY, RoutingConnectionBinding.KEY)
    class Snapshot(source: JSONObject, val mode: ExperienceMode) : JSONObject(source.toString())
    private fun capture(root: JSONObject) = JSONObject().also { out ->
        fields.forEach { if (root.has(it)) out.put(it, root.get(it)) }
    }
    private fun empty() = JSONObject().put("rules", JSONArray()).put("defaultTarget", "proxy")
        .put("excludedApps", JSONArray()).put("vpnApps", JSONArray()).put("appMode", "all")
    fun ensure(root: JSONObject) {
        if (!root.has(KEY)) root.put(KEY, JSONObject().put("version", 1).put("active", "expert")
            .put("simple", empty()).put("expert", JSONObject()))
    }
    fun select(root: JSONObject, mode: ExperienceMode) {
        ensure(root)
        val banks = root.getJSONObject(KEY)
        val old = banks.getString("active")
        if (old == mode.id) return
        RoutingGroups.suspendDns(root)
        val next = JSONObject(banks.getJSONObject(mode.id).toString())
        banks.put(old, capture(root)).put(mode.id, JSONObject()).put("active", mode.id)
        fields.forEach(root::remove)
        next.keys().forEach { root.put(it, next.get(it)) }
        RoutingGroups.ensure(root)
        RoutingGroups.resumeDns(root)
        RoutingGroups.synchronize(root)
    }
    fun project(source: JSONObject, mode: ExperienceMode): JSONObject = JSONObject(source.toString()).apply {
        select(this, mode); remove(KEY);RoutingGroups.ensure(this);RoutingGroups.synchronize(this)
        if(mode==ExperienceMode.SIMPLE&&optString("defaultTarget")=="direct"&&
            listOf("rules","vpnApps","excludedApps").all {optJSONArray(it)?.length()==0})
            put("defaultTarget","proxy")
        TextGeoRules.compile(this)
    }
    fun merge(source: JSONObject, candidate: JSONObject, mode: ExperienceMode): JSONObject {
        val result = JSONObject(source.toString())
        select(result, mode)
        val banks = result.getJSONObject(KEY)
        // Candidate includes shared DNS settings as well as the selected rule bank.
        result.keys().asSequence().toList().filter { it != KEY }.forEach(result::remove)
        candidate.keys().forEach { if (it != KEY) result.put(it, candidate.get(it)) }
        result.put(KEY, banks)
        return result
    }
    fun validate(source: JSONObject) {
        if (!source.has(KEY)) return
        val banks = source.getJSONObject(KEY)
        require(banks.keys().asSequence().toSet() == setOf("version", "active", "simple", "expert"))
        require(banks.getInt("version") == 1 && banks.getString("active") in setOf("simple", "expert"))
        for (mode in ExperienceMode.entries) {
            val bank = banks.getJSONObject(mode.id)
            if (banks.getString("active") == mode.id) require(bank.length() == 0)
            else {
                require(bank.keys().asSequence().all { it in fields })
                RoutingStore.validate(project(source, mode))
            }
        }
    }
}
