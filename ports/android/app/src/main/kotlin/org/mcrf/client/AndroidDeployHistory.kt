package org.mcrf.client

import org.json.JSONObject

/** History includes legacy endpoint/recipe records and new per-operation results; never merges their call groups. */
object AndroidDeployHistory {
    internal fun templateOptions(target:AndroidDeployTarget):JSONObject=AndroidDeployPlan.normalizedOptions(target).apply {
        if(has("vk_call_group")){remove("vk_call_group");put("vk_calls",org.json.JSONArray())}
        optJSONArray("swg_profiles")?.let {profiles->repeat(profiles.length()){
            val profile=profiles.getJSONObject(it)
            if(profile.has("call_group")){profile.remove("call_group");profile.put("vk_calls",org.json.JSONArray())}
        }}
    }
    data class Entry(val key: String,val recipe: String,val result: JSONObject)
    fun entries(records: JSONObject): List<Entry> = records.keys().asSequence().map { key ->
        val r=records.getJSONObject(key)
        val recipe=r.optString("recipe_id").ifBlank { key.substringAfterLast('/') }
        Entry(key,recipe,JSONObject(r.toString()))
    }.sortedWith(compareByDescending<Entry> { it.result.optLong("created_at",0) }.thenBy { it.key }).toList()
    fun latest(records: JSONObject,host: String,sshPort: String,recipe: String): JSONObject? {
        val endpoint="$host:$sshPort/$recipe"
        return entries(records).firstOrNull { it.recipe==recipe && (it.key==endpoint || it.key.startsWith("$endpoint/")) }?.result
    }
    fun resultKey(target: AndroidDeployTarget,recipe: String)="${target.host}:${target.sshPort}/$recipe/${target.operationId}"
    fun record(result: JSONObject,target: AndroidDeployTarget,recipe: String): JSONObject = JSONObject(result.toString()).apply {
        put("operation_id",target.operationId).put("recipe_id",recipe).put("created_at",System.currentTimeMillis())
        if(target.deploymentId.isNotEmpty())put("deployment_id",target.deploymentId).put("deployment_name",AndroidDeployInventory.name(target.deploymentName))
        put("transport_template",JSONObject().put("host",target.host).put("user",target.user)
            .put("sshPort",target.sshPort.toString()).put("panelPort",target.panelPort.toString())
            .put("domain",target.domain).put("options",templateOptions(target)))
        if(recipe=="swg") {
            if(target.options.optBoolean("create",true))target.options.optJSONArray("swg_profiles")?.let {put("swg_profiles",org.json.JSONArray(it.toString()))}
            val calls=target.options.optJSONArray("vk_calls") ?: org.json.JSONArray()
            if(calls.length()>0) put("vk_calls",org.json.JSONArray(calls.toString()))
            if(target.options.optBoolean("automatic_calls") && target.options.optString("vk_call_group").isNotBlank()) put("vk_call_group",target.options.getString("vk_call_group"))
        }
    }
}
