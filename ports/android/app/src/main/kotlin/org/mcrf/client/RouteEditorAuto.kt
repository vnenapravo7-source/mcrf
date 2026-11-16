package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Reuses the existing service selector worker for independent per-rule policies. */
internal object RouteEditorAuto {
    val modes=listOf(BsAutoSelectionPolicy.FASTEST,BsAutoSelectionPolicy.RECENT,AutoSelection.SEQUENTIAL)
    val pickerModes=listOf(BsAutoSelectionPolicy.FASTEST,AutoSelection.SEQUENTIAL)
    fun editableMode(saved:String?)=saved?.takeIf {it in pickerModes}?:BsAutoSelectionPolicy.FASTEST
    fun prepare(state:JSONObject,entries:List<ServerImport.Server>,probes:JSONObject,now:Long,allowed:Set<String>?=null):JSONObject {
        val out=JSONObject(state.toString())
        val pools=JSONObject()
        val rules=GeoDataConfig.orderedRules(out)
        repeat(rules.length()){index->
            val rule=rules.getJSONObject(index)
            if(!rule.optBoolean("enabled",true)||!rule.has(RouteServerPlan.AUTO))return@repeat
            RouteServerPlan.validate(rule)
            val ids=RouteServerPlan.ids(rule)
            val selected=ids.map {id->entries.singleOrNull {it.id==id}?:throw IllegalArgumentException("Сервер правила удалён. Выберите его заново.")}
            require(selected.all(RouteServerPlan::selectable)){"В правиле есть несовместимый сервер."}
            val bs=selected.first().whitelists
            require(selected.all {ConnectionSections.belongs(it,bs)}){"Для правила выберите один тип серверов: VPN или БС."}
            val options=rule.getJSONObject(RouteServerPlan.AUTO)
            val mode=options.optString("mode",BsAutoSelectionPolicy.FASTEST)
            val preferred=options.optString("preferred",ids.first())
            val candidates=selected.filter {allowed==null||it.id in allowed}
            val ranked=if(mode==AutoSelection.SEQUENTIAL)candidates else AutoSelection.startup(candidates,ids.toSet(),probes,now,if(bs)AutoSelection.BS_ENGINES else AutoSelection.LATENCY)
            val ordered=if(mode==BsAutoSelectionPolicy.RECENT)BsAutoSelectionPolicy.order(ranked,preferred){it.id}else ranked
            require(ordered.isNotEmpty()){"Нет доступных для автовыбора серверов правила."}
            // Runtime projection must also exclude these workers, not merely hide
            // them from the selector while still starting their transports.
            if(candidates.size==1) {
                rule.remove(RouteServerPlan.POOL);rule.remove(RouteServerPlan.AUTO)
                rule.put(RouteServerPlan.KEY,candidates.single().id)
                return@repeat
            }
            rule.put(RouteServerPlan.POOL,JSONArray(candidates.map {it.id}))
            if(candidates.none {it.id==preferred})options.put("preferred",ordered.first().id)
            val tag=RouteServerPlan.outbound(rule,"")
            pools.put(tag,JSONObject().put("tag",tag).put("ids",JSONArray(ordered.map {it.id})).put("bs",bs)
                .put("mode",mode).put("preferred",preferred).put("interval",options.getInt("interval"))
                .put("tolerance",options.getInt("tolerance")).put("failureOnly",options.optBoolean("failureOnly",false)))
        }
        if(pools.length()>0)out.put(RoutingConnectionBinding.RUNTIME,JSONObject().put("pools",pools)
            .put("interval",60).put("tolerance",0).put("failureOnly",false).put("networkDriven",false))
        return out
    }
}
