package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Only marked eligible entries; runtime validates connectivity, engine tier before ping. */
object BsPlan {
    /** Replace only supervisor metadata; preserve DNS, routes and the stable primary bridge. */
    fun refresh(source:JSONObject,entries:List<ServerImport.Server>,interval:Int):JSONObject {
        require(interval in 5..86400)
        require(entries.size in 1..256&&entries.all {AutoSelection.eligible(it,AutoSelection.BS_ENGINES)}) {"Нет отмеченных подключений БС."}
        val plan=JSONObject().put("interval",interval).put("candidates",JSONArray(entries.map {
            JSONObject().put("id",it.id).put("name",it.name).put("profile",JSONObject(requireNotNull(it.carrier).toString()))
        }))
        candidates(plan) // Validate the entire replacement before publishing it.
        return JSONObject(source.toString()).put("mcrf_bs",plan)
    }
    fun build(entries: List<ServerImport.Server>,interval:Int,tolerance:Int,dns:String):JSONObject {
        require(entries.isNotEmpty()&&entries.all { AutoSelection.eligible(it,AutoSelection.BS_ENGINES) }) { "Отметь и проверь подключения БС." }
        val sorted=entries.sortedBy { AutoSelection.ENGINE_PRIORITY.indexOf(it.protocol) }
        val first=sorted.first();val source=ServerImport.build(listOf(first),first.id,false,interval,tolerance,dns)
        return source.put("mcrf_bs",JSONObject().put("interval",interval).put("candidates",JSONArray(sorted.map {
            JSONObject().put("id",it.id).put("name",it.name).put("profile",JSONObject(it.carrier!!.toString()))
        })))
    }
    fun candidates(plan:JSONObject):List<JSONObject> {
        val interval=plan.getInt("interval");require(interval in 5..86400)
        val list=plan.getJSONArray("candidates");require(list.length() in 1..256)
        return (0 until list.length()).map { index -> JSONObject(list.getJSONObject(index).toString()).also {
            require(it.getString("id").matches(Regex("[0-9a-f]{64}")) && it.getString("name").length<=256)
            val profile=it.getJSONObject("profile");require(profile.getString("type") in AutoSelection.ENGINE_PRIORITY)
            CarrierProfile.fromJson(profile.toString(),profile)
        } }.sortedBy { AutoSelection.ENGINE_PRIORITY.indexOf(it.getJSONObject("profile").getString("type")) }
    }
}
