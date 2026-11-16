package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Only marked eligible entries; runtime validates connectivity, engine tier before ping. */
object BsPlan {
    /** A section assignment does not turn an ordinary outbound into a native carrier. */
    fun candidate(entry:ServerImport.Server,interval:Int,tolerance:Int=50,dns:String="Cloudflare"):JSONObject =
        JSONObject().put("id",entry.id).put("name",entry.name).apply {
            if(entry.carrier!=null)put("profile",JSONObject(entry.carrier.toString()))
            else {
                require(AutoSelection.eligible(entry,AutoSelection.BS_ENGINES)) {"Несовместимый сервер БС."}
                put("source",ServerImport.build(listOf(entry),entry.id,false,interval,tolerance,dns))
            }
        }
    private fun tier(candidate:JSONObject)=candidate.optJSONObject("profile")?.let {
        AutoSelection.ENGINE_PRIORITY.indexOf(it.getString("type"))
    }?:Int.MAX_VALUE
    /** Replace only supervisor metadata; preserve DNS, routes and the stable primary bridge. */
    fun refresh(source:JSONObject,entries:List<ServerImport.Server>,interval:Int):JSONObject {
        require(interval in 5..86400)
        require(entries.size in 1..256&&entries.all {AutoSelection.eligible(it,AutoSelection.BS_ENGINES)}) {"Нет отмеченных подключений БС."}
        val plan=JSONObject().put("interval",interval).put("candidates",JSONArray(entries.map {candidate(it,interval)}))
        candidates(plan) // Validate the entire replacement before publishing it.
        return JSONObject(source.toString()).put("mcrf_bs",plan)
    }
    fun build(entries: List<ServerImport.Server>,interval:Int,tolerance:Int,dns:String):JSONObject {
        require(entries.isNotEmpty()&&entries.all { AutoSelection.eligible(it,AutoSelection.BS_ENGINES) }) { "Отметь и проверь подключения БС." }
        val sorted=entries.sortedBy { AutoSelection.engineTier(it) }
        val first=sorted.first();val source=ServerImport.build(listOf(first),first.id,false,interval,tolerance,dns)
        return source.put("mcrf_bs",JSONObject().put("interval",interval).put("candidates",JSONArray(sorted.map {candidate(it,interval,tolerance,dns)})))
    }
    fun candidates(plan:JSONObject):List<JSONObject> {
        val interval=plan.getInt("interval");require(interval in 5..86400)
        val list=plan.getJSONArray("candidates");require(list.length() in 1..256)
        return (0 until list.length()).map { index -> JSONObject(list.getJSONObject(index).toString()).also {
            require(it.getString("id").matches(Regex("[0-9a-f]{64}")) && it.getString("name").length<=256)
            require(it.has("profile") xor it.has("source")) {"Некорректный кандидат БС."}
            val profile=it.optJSONObject("profile")
            if(profile!=null) {
                require(profile.getString("type") in AutoSelection.ENGINE_PRIORITY)
                CarrierProfile.fromJson(profile.toString(),profile)
            } else {
                val source=it.getJSONObject("source")
                require(!source.has("mcrf_bs")&&!source.has("mcrf_carrier"))
                require(source.getJSONObject("route").getString("final")=="node-${it.getString("id").take(16)}")
                ProfileConfig.build(source.toString(),false,ProfileConfig.DEFAULT_PORT)
            }
        } }.sortedBy(::tier)
    }
}
