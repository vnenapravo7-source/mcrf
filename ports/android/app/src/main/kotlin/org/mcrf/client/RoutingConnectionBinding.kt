package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent assignments are separate from rule drafts; runtime projection never writes them. */
internal object RoutingConnectionBinding {
    const val KEY="connectionBinding"
    const val RUNTIME="connectionBindingRuntime"
    const val DEFAULT="connectionBindingDefault"
    const val HELP="Привязка связывает группы правил с типами подключения: «VPN» — с обычными VPN-серверами, «БС» — с VK Turn и OpenFlux.\n\nВ группе «Авто VPN + БС» тип подключения меняется по состоянию сети, только если включены и эта привязка, и «Автоопределение БС» в настройках «VPN и БС». Если автоопределение выключено, используется текущий тип подключения без автоматического перехода VPN ↔ БС. В отдельных правилах тип подключения не выбирается.\n\nСерверы берутся из двух списков ниже. Не выбрано — используются все подходящие серверы этого типа. Выбор сервера внутри типа учитывает режим, интервал и отклонение из настроек «VPN и БС».\n\nПрежние назначения серверов в правилах сохраняются и снова действуют после отключения привязки."
    fun enabled(state:JSONObject)=state.optJSONObject(KEY)?.optBoolean("enabled",false)==true
    fun networkDriven(state:JSONObject,detectionEnabled:Boolean)=enabled(state)&&RoutingGroups.active(state)=="auto"&&detectionEnabled
    fun selected(state:JSONObject,bs:Boolean):List<String> = state.optJSONObject(KEY)?.optJSONArray(if(bs)"bs"else"vpn")?.let {a->(0 until a.length()).map {a.getString(it)}}?:emptyList()
    fun assign(state:JSONObject,bs:Boolean,ids:List<String>){val binding=state.optJSONObject(KEY)?:JSONObject().also{state.put(KEY,it)};binding.put(if(bs)"bs"else"vpn",JSONArray(ids));validate(state)}
    fun validate(state:JSONObject){
        if(state.has(KEY))require(state.get(KEY) is JSONObject){"Некорректные настройки привязки маршрутов."}
        state.optJSONObject(KEY)?.let {binding->
            require(binding.keys().asSequence().all{it in setOf("enabled","vpn","bs")})
            if(binding.has("enabled"))require(binding.get("enabled") is Boolean)
            for(bs in listOf(false,true)){val field=if(bs)"bs"else"vpn";if(binding.has(field))require(binding.get(field) is JSONArray);val ids=selected(state,bs);require(ids.size<=256&&ids.distinct().size==ids.size&&ids.all{it.matches(Regex("[0-9a-f]{64}"))}){"Некорректный список серверов привязки."}}
        }
    }
    // The same family split as the VPN/BS sections. OpenFlux is explicitly admitted
    // here, not silently enabled in unrelated legacy automatic pools.
    fun eligible(entry:ServerImport.Server,bs:Boolean)=ConnectionSections.belongs(entry,bs)&&(!bs||entry.protocol in AutoSelection.ENGINE_PRIORITY)&&
        (RouteServerPlan.selectable(entry)||entry.protocol=="openflux"&&entry.carrier!=null&&entry.configuration==null)
    fun admitted(state:JSONObject,entries:List<ServerImport.Server>,bs:Boolean):List<ServerImport.Server>{
        if(!enabled(state))return entries
        val ids=selected(state,bs).toSet()
        return entries.filter{eligible(it,bs)&&(ids.isEmpty()||it.id in ids)}
    }
    /** Detector admission only; never save/activate the BS group's rules. */
    fun bsDetectorEntries(context:Context,state:JSONObject,entries:List<ServerImport.Server>,probes:JSONObject):List<ServerImport.Server>{
        val scope=JSONObject(state.toString());RoutingGroups.select(scope,"bs");scope.put("defaultTarget","proxy")
        val runtime=prepare(context,scope,entries,true,probes)
        val ids=runtime.getJSONObject(RUNTIME).getJSONObject("pools").getJSONObject("bs").getJSONArray("ids")
        return (0 until ids.length()).map{i->entries.single{it.id==ids.getString(i)}}
    }
    data class Policy(val mode:String,val lastVpn:String,val lastBs:String,val interval:Int,val tolerance:Int,val failureOnly:Boolean,val detectionEnabled:Boolean=false)
    fun policy(context:Context):Policy {val p=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE);return Policy(BsAutoSelectionPolicy.selected(p),BsAutoSelectionPolicy.last(p,false),BsAutoSelectionPolicy.last(p,true),AutoCheckIntervals.seconds(AutoCheckIntervals.Kind.SELECTION,p::getInt),AutoSelectionDeviation.millis(p),AutoSelectionDeviation.failureOnly(p),p.getBoolean(BsDetectionMonitor.KEY,false))}
    fun initial(state:JSONObject,ids:List<String>):String {
        val pools=state.getJSONObject(RUNTIME).getJSONObject("pools")
        return pools.keys().asSequence().map{pools.getJSONObject(it).getJSONArray("ids")}.map{a->(0 until a.length()).map{a.getString(it)}}.first{it.toSet()==ids.toSet()}.first()
    }
    fun prepare(context:Context,state:JSONObject,entries:List<ServerImport.Server>,bs:Boolean,probes:JSONObject=JSONObject()):JSONObject = prepare(state,entries,bs,policy(context),probes,System.currentTimeMillis())
    fun prepare(state:JSONObject,entries:List<ServerImport.Server>,bs:Boolean,policy:Policy,probes:JSONObject,now:Long):JSONObject {
        if(!enabled(state))return JSONObject(state.toString())
        validate(state);require(policy.mode in BsAutoSelectionPolicy.modes)
        val out=JSONObject(state.toString());val pools=JSONObject();val active=RoutingGroups.active(state)
        fun pool(type:String):List<String> {
            val family=when(type){"vpn"->false;"bs"->true;else->bs};val name=if(family)"bs"else"vpn"
            pools.optJSONObject(name)?.let {value->return value.getJSONArray("ids").let{a->(0 until a.length()).map{a.getString(it)}}}
            val assigned=selected(state,family).toSet();val all=entries.filter{eligible(it,family)}.distinctBy{it.id}
            require(assigned.all{id->all.any{it.id==id}}){"Назначенный сервер ${if(family)"БС"else"VPN"} удалён или несовместим. Обновите список в «Маршрутах»; другой сервер не подставлен."}
            val candidates=all.filter{assigned.isEmpty()||it.id in assigned}
            val last=if(family)policy.lastBs else policy.lastVpn
            val ranked=candidates.sortedWith(compareBy<ServerImport.Server>{if(family)AutoSelection.ENGINE_PRIORITY.indexOf(it.protocol).let{tier->if(tier<0)Int.MAX_VALUE else tier}else 0}.thenBy{entry->probes.optJSONObject(entry.id)?.let{if(it.optLong("delay",-1)>=0&&now-it.optLong("at",0) in 0..300000)it.getLong("delay")else Long.MAX_VALUE}?:Long.MAX_VALUE})
            val chosen=if(policy.mode==BsAutoSelectionPolicy.OFF){if(ranked.size==1)ranked else ranked.filter{it.id==last}}else BsAutoSelectionPolicy.targets(ranked,last,policy.mode){it.id}
            require(chosen.isNotEmpty()){if(policy.mode==BsAutoSelectionPolicy.OFF)"Автовыбор выключен: назначьте один сервер ${if(family)"БС"else"VPN"} или выберите его вручную в соответствующем разделе."else"Нет подходящих серверов ${if(family)"БС"else"VPN"} для маршрутов."}
            require(chosen.size<=32&&chosen.count{it.carrier!=null}<=8){"Для маршрутов выберите не более 32 серверов, включая до 8 транспортов БС."}
            val ids=chosen.map{it.id};pools.put(name,JSONObject().put("ids",JSONArray(ids)).put("bs",family).put("mode",policy.mode).put("preferred",last))
            return ids
        }
        fun bind(rule:JSONObject,type:String){rule.remove(RouteServerPlan.KEY);rule.remove(RouteServerPlan.POOL);val ids=pool(type);if(ids.size==1)rule.put(RouteServerPlan.KEY,ids.single())else rule.put(RouteServerPlan.POOL,JSONArray(ids))}
        val type=if(active=="auto")"auto"else active
        fun project(rules:JSONArray){repeat(rules.length()){i->val rule=rules.getJSONObject(i);rule.remove(RouteServerPlan.KEY);rule.remove(RouteServerPlan.POOL);rule.remove("connectionType");if(rule.optBoolean("enabled",true)&&rule.optString("target","proxy")=="proxy")bind(rule,type)}}
        project(out.getJSONArray("rules"));out.optJSONObject("geoData")?.optJSONArray("providerRules")?.let(::project)
        // The assigned family also owns proxy DNS/app policies/legacy geo rules,
        // even when unmatched application traffic is explicitly Direct/Block.
        out.put(DEFAULT,JSONObject().apply{bind(this,type)})
        out.put(RUNTIME,JSONObject().put("pools",pools).put("interval",policy.interval).put("tolerance",policy.tolerance).put("failureOnly",policy.failureOnly).put("networkDriven",networkDriven(state,policy.detectionEnabled)))
        return out
    }
}
