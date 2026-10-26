package org.mcrf.client

import org.json.JSONObject

/** Explicit selectors rather than autonomous URL-test: OFF/RECENT/failure-only
 * must not be approximated by a huge tolerance. Called on the service worker. */
internal class RouteBindingSelection(settings:JSONObject,entries:List<ServerImport.Server>){
    private val runtime=JSONObject(settings.getJSONObject(RoutingConnectionBinding.RUNTIME).toString())
    private val protocols=entries.associate{it.id to it.protocol}
    private var due=0L
    private var cursor=0
    private val chosen=mutableMapOf<String,String>()
    fun tick(now:Long,health:(String)->Long,select:(String,String)->Unit,cancelled:()->Boolean){
        if(now<due||cancelled())return
        due=now+runtime.getInt("interval").coerceIn(5,86400)*1000L
        val pools=runtime.getJSONObject("pools")
        for(key in pools.keys()){
            if(cancelled())return
            val pool=pools.getJSONObject(key);val array=pool.getJSONArray("ids");val ids=(0 until array.length()).map{array.getString(it)}
            if(ids.size<2||pool.getString("mode")==BsAutoSelectionPolicy.OFF)continue
            val group=RouteServerPlan.poolTag(ids);val current=chosen[group]?:ids.first()
            fun delay(id:String):Long {if(cancelled())return -1;val value=runCatching{health(RouteServerPlan.tag(id))}.getOrDefault(-1);return if(cancelled())-1 else value}
            val currentDelay=delay(current);if(cancelled())return
            if(currentDelay>=0&&(runtime.getBoolean("failureOnly")||pool.getString("mode")==BsAutoSelectionPolicy.RECENT&&current==pool.optString("preferred")))continue
            val candidates=BsAutoSelectionPolicy.attempts(ids,cursor,8)
            val delays=mutableMapOf(current to currentDelay)
            for(id in candidates){if(cancelled())return;if(id!=current)delays[id]=delay(id)}
            val next=choose(ids,current,delays,pool.getBoolean("bs"),pool.getString("mode"),pool.optString("preferred"),runtime.getInt("tolerance"),runtime.getBoolean("failureOnly"),protocols)
            if(next!=null&&next!=current&&!cancelled()){select(group,RouteServerPlan.tag(next));if(!cancelled())chosen[group]=next}
        }
        cursor++
    }
    companion object {
        val preferenceKeys=setOf(BsAutoSelectionPolicy.KEY,BsAutoSelectionPolicy.LAST_VPN,BsAutoSelectionPolicy.LAST_BS,AutoCheckIntervals.SELECTION,"tolerance",AutoSelectionDeviation.FAILURE_ONLY,"selected")
        fun choose(ids:List<String>,current:String,delays:Map<String,Long>,bs:Boolean,mode:String,preferred:String,tolerance:Int,failureOnly:Boolean,protocols:Map<String,String>):String? {
            if(mode==BsAutoSelectionPolicy.OFF)return null
            val old=delays[current]?:-1
            if(old>=0&&failureOnly)return current
            val live=ids.filter{(delays[it]?:-1)>=0};if(live.isEmpty())return null
            if(mode==BsAutoSelectionPolicy.RECENT)return live.firstOrNull{it==preferred}?:live.first()
            fun tier(id:String)=if(bs)AutoSelection.ENGINE_PRIORITY.indexOf(protocols[id].orEmpty()).let{if(it<0)Int.MAX_VALUE else it}else 0
            val best=live.minWith(compareBy<String>{tier(it)}.thenBy{delays.getValue(it)})
            return if(old<0||tier(best)<tier(current)||tier(best)==tier(current)&&delays.getValue(best)+tolerance<old)best else current
        }
    }
}
