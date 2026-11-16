package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Only network-dependent settings, never a copy of the catalogue or unrelated preferences. */
data class NetworkProfileConfiguration(val routing:String,val pool:String,val preferences:String) {
    fun json()=JSONObject().put("routing",JSONObject(routing)).put("pool",JSONArray(pool)).put("preferences",JSONObject(preferences))
    companion object {
        val booleanKeys=setOf("automatic","autoDetectBs","autoSelectionFailureOnly")
        val integerKeys=setOf("interval","tolerance","bsDetectionInterval")
        val stringKeys=setOf("selected","bsLastManualVpn","bsLastManualBs","bsDetectionSelection","autoPolicy")
        val keys=booleanKeys+integerKeys+stringKeys
        fun capture(routing:String,catalogue:String,preferences:JSONObject):NetworkProfileConfiguration {
            val selected=JSONObject()
            keys.forEach {if(preferences.has(it))selected.put(it,preferences.get(it))}
            return fromJson(JSONObject().put("routing",JSONObject(routing)).put("pool",JSONObject(catalogue.ifBlank {"{}"}).optJSONArray("autoServers")?:JSONArray()).put("preferences",selected))
        }
        fun fromJson(value:JSONObject):NetworkProfileConfiguration {
            require(value.keys().asSequence().toSet()==setOf("routing","pool","preferences"))
            val routing=value.getJSONObject("routing")
            RoutingStore.validate(routing)
            val pool=value.getJSONArray("pool")
            require(pool.length()<=4096)
            repeat(pool.length()){require(pool.get(it) is String&&pool.getString(it).matches(Regex("[a-f0-9]{64}")))}
            val prefs=value.getJSONObject("preferences")
            require(prefs.keys().asSequence().all {it in keys})
            booleanKeys.filter(prefs::has).forEach {require(prefs.get(it) is Boolean)}
            integerKeys.filter(prefs::has).forEach {require(prefs.get(it) is Int)}
            stringKeys.filter(prefs::has).forEach {require(prefs.get(it) is String)}
            require(prefs.optString("bsDetectionSelection","fastest") in setOf("off","fastest","recent"))
            require(prefs.optString("autoPolicy",AutoSelection.LATENCY) in setOf(AutoSelection.LATENCY,AutoSelection.BS_ENGINES))
            require(prefs.optInt("interval",60) in 5..86400&&prefs.optInt("tolerance",50) in 0..10000)
            require(prefs.optInt("bsDetectionInterval",60) in BsDetectionInterval.seconds)
            for(key in listOf("selected","bsLastManualVpn","bsLastManualBs"))require(prefs.optString(key,"").let {it.isEmpty()||it.matches(Regex("[a-f0-9]{64}"))})
            return NetworkProfileConfiguration(routing.toString(),pool.toString(),prefs.toString())
        }
    }
}

/** Pure switch transaction. Outgoing user edits are retained before applying the next profile. */
internal object NetworkProfileSwitch {
    const val DEFAULT="default"
    data class Result(val section:JSONObject,val target:NetworkProfileConfiguration?,val changed:Boolean)
    fun transition(section:JSONObject,network:ConditionEnvironment,current:NetworkProfileConfiguration,safeMode:Boolean=false,
        retainEdits:Boolean=true,now:java.time.Instant=java.time.Instant.now(),zone:java.time.ZoneId=java.time.ZoneId.systemDefault()):Result {
        if(safeMode)return Result(section,null,false)
        val profiles=ConditionProfiles.fromJson(section)
        if(profiles.isEmpty()&&!section.has("fallback"))return Result(section,null,false)
        val active=section.optString("activeId","")
        val target=ConditionProfiles.resolve(profiles,network,false,now,zone)?.id?:DEFAULT
        val migrated=profiles.map {if(it.configuration==null)it.copy(configuration=current)else it}
        var fallback=section.optJSONObject("fallback")?.let(NetworkProfileConfiguration::fromJson)?:current
        val retained=migrated.map {if(retainEdits&&it.id==active&&active!=target)it.copy(configuration=current)else it}
        if(retainEdits&&active==DEFAULT&&target!=active)fallback=current
        val next=ConditionProfiles.json(retained).put("activeId",target).put("fallback",fallback.json())
        return Result(next,if(active==target)null else if(target==DEFAULT)fallback else retained.first {it.id==target}.configuration,
            !ChangeHistoryStore.equivalent(section.toString(),next.toString()))
    }
    fun apply(configuration:NetworkProfileConfiguration,values:Map<String,String>):Map<String,String> {
        val next=values.toMutableMap()
        val catalogue=JSONObject(values.getValue("connections.enc").ifBlank {"{\"version\":1,\"servers\":[],\"subscriptions\":[]}"})
        catalogue.put("autoServers",JSONArray(configuration.pool))
        next["connections.enc"]=catalogue.toString()
        next["routing.enc"]=configuration.routing
        val prefs=JSONObject(values.getValue("preferences.enc").ifBlank {"{}"})
        val saved=JSONObject(configuration.preferences)
        // Older profiles do not contain newer settings; absence is not a request to reset them.
        NetworkProfileConfiguration.keys.filter(saved::has).forEach {prefs.put(it,saved.get(it))}
        next["preferences.enc"]=prefs.toString()
        return next
    }
}
