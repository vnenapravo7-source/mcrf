package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Independent expiring rules; permanent rules are never replaced or restored. */
internal object TemporaryRules {
    const val KEY="temporaryRules"
    data class Clock(val wall:Long,val elapsed:Long,val boot:Int,val generation:String)
    private fun copy(value:JSONObject)=JSONObject(value.toString())
    fun rows(root:JSONObject):List<JSONObject> = root.optJSONObject(KEY)?.getJSONArray("sessions")?.let {a->(0 until a.length()).map {a.getJSONObject(it)}}.orEmpty()
    fun rule(root:JSONObject,id:String):JSONObject?=root.getJSONArray("rules").let {a->(0 until a.length()).map {a.getJSONObject(it)}.singleOrNull {it.getString("id")==id}}
    fun scope(root:JSONObject,mode:String,group:String):JSONObject {
        val base=copy(root).apply {remove(KEY);removeLegacy(this)}
        val view=if(base.has(ExperienceRouting.KEY))ExperienceRouting.project(base,ExperienceMode.entries.single {it.id==mode})else base
        RoutingGroups.select(view,group)
        return view
    }
    fun expired(row:JSONObject,now:Clock):Boolean=now.wall>=row.getLong("until")||now.wall<row.getLong("started")||
        row.getString("generation")!=now.generation||row.getInt("boot")==now.boot&&now.elapsed>=row.getLong("elapsedUntil")
    fun start(root:JSONObject,mode:String,group:String,rule:JSONObject,minutes:Int,now:Clock):String {
        require(mode==ExperienceMode.EXPERT.id&&group in RoutingGroups.ids){"Временные правила доступны в экспертном режиме."}
        require(minutes in listOf(5,15,30,60)){"Выберите срок временного правила."}
        require(rows(root).size<32){"Одновременно допускается до 32 временных правил."}
        val view=scope(root,mode,group)
        require(TemporaryRules.rule(view,rule.getString("id"))==null&&rows(root).none {it.getJSONObject("rule").getString("id")==rule.getString("id")}){"Такое правило уже существует."}
        val candidate=copy(rule).put("enabled",true)
        val check=copy(view).put("rules",JSONArray().put(candidate));RoutingGroups.synchronize(check);RoutingStore.validate(check)
        val sid=java.util.UUID.randomUUID().toString();val duration=minutes*60_000L
        val row=JSONObject().put("id",sid).put("mode",mode).put("group",group).put("rule",candidate)
            .put("started",now.wall).put("until",Math.addExact(now.wall,duration)).put("boot",now.boot)
            .put("elapsedUntil",Math.addExact(now.elapsed,duration)).put("generation",now.generation)
        root.put(KEY,JSONObject().put("version",1).put("sessions",JSONArray(rows(root)+row)))
        return sid
    }
    fun stop(root:JSONObject,id:String) {
        val remaining=rows(root).filter {it.getString("id")!=id}
        if(remaining.isEmpty())root.remove(KEY)else root.put(KEY,JSONObject().put("version",1).put("sessions",JSONArray(remaining)))
    }
    fun reconcile(root:JSONObject,now:Clock):Boolean {
        val expired=rows(root).filter {expired(it,now)}
        expired.forEach {stop(root,it.getString("id"))}
        return expired.isNotEmpty()
    }
    fun active(root:JSONObject,mode:String,group:String,now:Clock)=rows(root).filter {it.getString("mode")==mode&&it.getString("group")==group&&!expired(it,now)}
    fun project(state:JSONObject,now:Clock):JSONObject {
        val out=copy(state);removeLegacy(out)
        val mode=state.optJSONObject(KEY)?.optString("viewMode","expert")?:"expert"
        val base=out.getJSONArray("rules")
        val ids=(0 until base.length()).map {base.getJSONObject(it).getString("id")}.toSet()
        val temporary=active(state,mode,RoutingGroups.active(state),now).map {copy(it.getJSONObject("rule"))}.filter {it.getString("id") !in ids}
        out.put("rules",JSONArray(temporary+(0 until base.length()).map {base.getJSONObject(it)}))
        out.remove(KEY);TextGeoRules.compile(out);TextGeoRules.stripSources(out)
        return out
    }
    /** Retire old overlays by discarding metadata only. Never replay saved originals. */
    fun removeLegacy(root:JSONObject):Boolean {
        var changed=root.has("ruleSandbox")
        root.remove("ruleSandbox")
        root.optJSONArray("rules")?.let {rules->repeat(rules.length()){i->
            val row=rules.getJSONObject(i);if(row.has("ruleSandboxFamily")){row.remove("ruleSandboxFamily");changed=true}
        }}
        root.optJSONObject(ExperienceRouting.KEY)?.let {banks->for(mode in listOf("simple","expert"))banks.optJSONObject(mode)?.let {if(removeLegacy(it))changed=true}}
        root.optJSONObject(RoutingGroups.KEY)?.optJSONArray("groups")?.let {groups->repeat(groups.length()){i->
            groups.getJSONObject(i).optJSONObject("data")?.let {if(removeLegacy(it))changed=true}
        }}
        return changed
    }
    fun validate(root:JSONObject) {
        require(!root.has(KEY)||root.get(KEY) is JSONObject){"Повреждены временные правила."}
        val doc=root.optJSONObject(KEY)?:return
        require(doc.keys().asSequence().all {it in setOf("version","sessions","viewMode")}&&doc.getInt("version")==1&&rows(root).size<=32)
        if(doc.has("viewMode"))require(doc.getString("viewMode") in setOf("simple","expert"))
        val ids=hashSetOf<String>();val ruleIds=hashSetOf<String>()
        for(row in rows(root)) {
            require(row.keys().asSequence().toSet()==setOf("id","mode","group","rule","started","until","boot","elapsedUntil","generation"))
            require(row.getString("id").matches(Regex("[a-f0-9-]{36}"))&&ids.add(row.getString("id")))
            require(row.getString("mode")=="expert"&&row.getString("group") in RoutingGroups.ids)
            val duration=row.getLong("until")-row.getLong("started")
            require(row.getLong("started")>=0&&duration in 60_000L..86_400_000L&&row.getLong("elapsedUntil")>=duration&&row.getInt("boot")>=0&&row.getString("generation").length<=128)
            val temporary=row.getJSONObject("rule");require(ruleIds.add(temporary.getString("id")))
            val check=scope(root,row.getString("mode"),row.getString("group"))
            require(rule(check,temporary.getString("id"))==null){"Временное правило не может заменять постоянное."}
            check.put("rules",JSONArray().put(copy(temporary)));RoutingGroups.synchronize(check);RoutingStore.validate(check)
        }
    }
}
