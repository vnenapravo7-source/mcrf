package org.mcrf.client

import org.json.JSONObject

internal object RoutingConflictAnalyzer {
    data class Finding(val earlierId:String,val laterId:String,val earlierName:String,val laterName:String,
        val earlierPosition:Int,val laterPosition:Int,val exact:Boolean,val shadowed:Boolean,val sameOutput:Boolean,val explanation:String)
    data class Report(val findings:List<Finding>,val enabledRules:Int,val truncated:Boolean,val group:String)
    private data class Row(val value:JSONObject,val position:Int,val expression:RoutingConflictLogic.Expr)
    fun inspect(state:JSONObject,resolve:(String)->JSONObject?={null},maxFindings:Int=128):Report {
        require(maxFindings in 1..512)
        val prepared=JSONObject(state.toString()).apply {remove(RoutingGroups.KEY);TextGeoRules.compile(this)}
        val selectors=GeoDataConfig.value(prepared).getJSONArray("selectors")
        fun label(tag:String):String {
            val index=tag.removePrefix("mcrf-geo-").toIntOrNull()
            return if(tag.startsWith("mcrf-geo-")&&index!=null&&index in 0 until selectors.length())selectors.getJSONObject(index).let {"${it.getString("kind")}:${it.getString("code")}"}else"готовый набор"
        }
        val logic=RoutingConflictLogic(resolve,::label);val rows=mutableListOf<Row>();val findings=mutableListOf<Finding>()
        var truncated=false;val source=prepared.getJSONArray("rules")
        val enabled=(0 until source.length()).count {source.getJSONObject(it).optBoolean("enabled",true)}
        fun output(rule:JSONObject):String=JSONObject().put("target",rule.optString("target","proxy"))
            .put("dns",rule.optJSONObject("dns")?:JSONObject()).apply {
                if(!RoutingConnectionBinding.enabled(state))for(key in listOf(RouteServerPlan.KEY,RouteServerPlan.POOL,RouteServerPlan.AUTO))if(rule.has(key))put(key,rule.get(key))
            }.toString()
        try {
            repeat(source.length()){i->val rule=source.getJSONObject(i)
                if(rule.optBoolean("enabled",true)) {
                    val matches=RouteRules.matches(rule)
                    rows.add(Row(rule,i+1,logic.either((0 until matches.length()).map {logic.parse(matches.getJSONObject(it))})))
                }}
            pairs@ for(i in rows.indices)for(j in i+1 until rows.size) {
                logic.step();val earlier=rows[i];val later=rows[j]
                val match=logic.solve(logic.both(earlier.expression,later.expression))
                if(match.kind==RoutingConflictLogic.Kind.EMPTY)continue
                val shadowed=logic.solve(logic.both(later.expression,logic.not(earlier.expression))).kind==RoutingConflictLogic.Kind.EMPTY
                val same=ChangeHistoryStore.equivalent(output(earlier.value),output(later.value))
                val order=if(shadowed)"Верхнее правило полностью перекрывает нижнее."else"На пересечении первым срабатывает верхнее правило."
                findings.add(Finding(earlier.value.getString("id"),later.value.getString("id"),earlier.value.getString("name"),later.value.getString("name"),earlier.position,later.position,
                    match.kind==RoutingConflictLogic.Kind.EXACT,shadowed,same,"$order ${if(same)"Выход одинаковый: возможное дублирование."else"Выходы или DNS различаются."} ${match.explanation}"))
                if(findings.size>=maxFindings){truncated=true;break@pairs}
            }
        } catch(_:RoutingConflictLogic.Limit) {truncated=true}
        return Report(findings,enabled,truncated,RoutingGroups.active(state))
    }
    /** Disable only explicitly confirmed IDs from this report. Preserve every row and its order. */
    fun resolve(state:JSONObject,report:Report,winner:String?,disable:Set<String>):JSONObject {
        if(winner==null) {require(disable.isEmpty());return JSONObject(state.toString())}
        require(RoutingGroups.active(state)==report.group)
        val peers=report.findings.flatMap {listOf(it.earlierId to it.laterId,it.laterId to it.earlierId)}.filter {it.first==winner}.mapTo(hashSetOf()){it.second}
        require(peers.containsAll(disable)&&winner !in disable&&peers.isNotEmpty())
        val result=JSONObject(state.toString());val rules=result.getJSONArray("rules")
        val ids=(0 until rules.length()).map {rules.getJSONObject(it).getString("id")}.toSet()
        require(winner in ids&&ids.containsAll(disable))
        repeat(rules.length()){i->val rule=rules.getJSONObject(i);if(rule.getString("id") in disable)rule.put("enabled",false)}
        return result
    }
}
