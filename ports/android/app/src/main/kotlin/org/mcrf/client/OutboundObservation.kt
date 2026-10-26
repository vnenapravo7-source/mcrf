package org.mcrf.client

import org.json.JSONObject

/** Only live groups need an IPC query. A concrete final outbound cannot switch. */
internal object OutboundObservation {
    fun fixedFinal(config:JSONObject?):String?=fixed(config,config?.optJSONObject("route")?.optString("final").orEmpty())
    /** Only actual manual selector choices, never a default/first-member guess.
     * Per-rule-only failures and automatic groups do not trigger additional RPCs. */
    fun failedManualFinal(config:JSONObject?,automatic:Boolean,failed:Set<String>,observe:(String)->String):String? {
        if(config==null||automatic||failed.isEmpty())return null
        val list=config.optJSONArray("outbounds")?:return null
        val outputs=(0 until list.length()).mapNotNull {list.optJSONObject(it)}.associateBy {it.optString("tag")}
        val final=config.optJSONObject("route")?.optString("final").orEmpty()
        fun members(node:JSONObject):List<String> = node.optJSONArray("outbounds")?.let {values->
            (0 until values.length()).mapNotNull {values.opt(it) as? String}
        }.orEmpty()
        val pending=java.util.ArrayDeque<String>();pending.add(final)
        val reachable=hashSetOf<String>()
        var relevant=false
        while(pending.isNotEmpty()){
            val tag=pending.removeFirst()
            if(!reachable.add(tag))continue
            val node=outputs[tag]?:continue
            when(node.optString("type")){
                "selector"->members(node).forEach(pending::addLast)
                "urltest"->Unit
                else->if(tag in failed)relevant=true
            }
        }
        if(!relevant)return null
        val seen=hashSetOf<String>();var tag=final
        repeat(16){
            if(!seen.add(tag))return null
            val node=outputs[tag]?:return null
            when(node.optString("type")){
                "selector"->{val selected=observe(tag);if(selected !in members(node))return null;tag=selected}
                "urltest"->return null
                else->return tag.takeIf {it in failed}
            }
        }
        return null
    }
    fun fixed(config:JSONObject?,target:String):String? {
        if(config==null||target.isEmpty())return null
        val outputs=config.optJSONArray("outbounds")?:return null
        val node=(0 until outputs.length()).mapNotNull {outputs.optJSONObject(it)}
            .firstOrNull {it.optString("tag")==target}?:return null
        return if(node.optString("type") in setOf("urltest","selector"))null else target
    }
}
