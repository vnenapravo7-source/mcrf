package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Beta: pure wall-clock evaluation; no alarms, services, credentials or VPN commands here. */
internal object BehaviorSchedule {
    const val KEY="behaviorSchedule"
    enum class Network(val title:String) { ANY("Любая сеть"), WIFI("Wi-Fi"), MOBILE("Мобильная сеть") }
    enum class Toggle(val key:String,val title:String) {
        FAILURE_ONLY("autoSelectionFailureOnly","Менять сервер только при сбое"),
        AUTOMATIC("automatic","Автовыбор"), DETECT_BS("autoDetectBs","Автоопределение БС")
    }
    data class Window(val days:Set<DayOfWeek>,val startMinute:Int,val endMinute:Int) {
        init {require(days.isNotEmpty()&&startMinute in 0..1439&&endMinute in 0..1439)}
        fun matches(now:java.time.ZonedDateTime):Boolean {
            val minute=now.hour*60+now.minute
            val anchor=if(startMinute>endMinute&&minute<endMinute)now.toLocalDate().minusDays(1)else now.toLocalDate()
            val inRange=when {
                startMinute==endMinute->true
                startMinute<endMinute->minute>=startMinute&&minute<endMinute
                else->minute>=startMinute||minute<endMinute
            }
            return inRange&&anchor.dayOfWeek in days
        }
    }
    data class Conditions(val windows:List<Window> = emptyList(),val zoneId:String?=null,
                          val network:Network=Network.ANY,val roaming:Boolean?=null,
                          val charging:Boolean?=null,val batteryAtMost:Int?=null) {
        init {require(windows.size<=16);zoneId?.let {ZoneId.of(it)};require(batteryAtMost==null||batteryAtMost in 0..100)}
        fun matches(now:Instant,systemZone:ZoneId,environment:Environment):Boolean {
            if(windows.isNotEmpty()&&windows.none {it.matches(now.atZone(zoneId?.let(ZoneId::of)?:systemZone))})return false
            if(network==Network.WIFI&&!environment.network.wifi||network==Network.MOBILE&&!environment.network.cellular)return false
            if(roaming!=null&&(!environment.network.cellular||environment.network.roaming!=roaming))return false
            if(charging!=null&&environment.charging!=charging)return false
            if(batteryAtMost!=null&&(environment.batteryPercent==null||environment.batteryPercent> batteryAtMost))return false
            return true
        }
    }
    data class Action(val profileId:String?=null,val toggles:Map<Toggle,Boolean> = emptyMap(),
                      val allowActiveVpnReconfiguration:Boolean=false) {
        init {
            require((profileId!=null).xor(toggles.isNotEmpty())) {"Выберите профиль или безопасные переключатели."}
            require(profileId==null||profileId.matches(Regex("[A-Za-z0-9-]{1,64}"))&&profileId!=NetworkProfileSwitch.DEFAULT)
        }
    }
    data class Rule(val id:String,val name:String,val priority:Int=0,val enabled:Boolean=true,
                    val conditions:Conditions=Conditions(),val action:Action) {
        init {require(id.matches(Regex("[A-Za-z0-9-]{1,64}")));require(name.isNotBlank()&&name.length<=80&&name.none(Char::isISOControl));require(priority in 0..1000)}
    }
    data class Settings(val enabled:Boolean=false,val rules:List<Rule> = emptyList()) {
        init {require(rules.size<=32&&rules.map {it.id}.distinct().size==rules.size)}
    }
    data class Environment(val network:ConditionEnvironment=ConditionEnvironment(),val charging:Boolean?=null,
                           val batteryPercent:Int?=null,val vpnActive:Boolean=false) {
        init {require(batteryPercent==null||batteryPercent in 0..100)}
    }
    data class Decision(val rule:Rule?,val nextRun:Instant?,val deferredForVpn:Boolean)
    /** Profile-owned schedules need no second global switch or independent action approval. */
    fun nextProfileBoundary(profiles:List<ConditionProfile>,now:Instant,zone:ZoneId,environment:Environment):Instant? =
        profiles.asSequence().filter {it.enabled&&it.configuration!=null&&it.windows.isNotEmpty()&&ConditionProfiles.matchesNetwork(it,environment.network)}.mapNotNull {profile->
            val windows=profile.windows.map {Window(it.days.map(java.time.DayOfWeek::of).toSet(),it.startMinute,it.endMinute)}
            evaluate(Settings(true,listOf(Rule("profile-clock","Профиль",conditions=Conditions(windows),
                action=Action(profileId=profile.id)))),now,zone,environment,setOf(profile.id)).nextRun
        }.minOrNull()
    private fun winner(settings:Settings,now:Instant,zone:ZoneId,environment:Environment,profiles:Set<String>):Rule? =
        if(!settings.enabled)null else settings.rules.asSequence().filter {
            it.enabled&&(it.action.profileId==null||it.action.profileId in profiles)&&it.conditions.matches(now,zone,environment)
        }.sortedWith(compareByDescending<Rule> {it.priority}.thenBy {it.id}).firstOrNull()

    /** Highest priority, then lexicographic ID; a blocked winner never falls through to a lower rule. */
    fun evaluate(settings:Settings,now:Instant,zone:ZoneId,environment:Environment,profiles:Set<String>):Decision {
        val selected=winner(settings,now,zone,environment,profiles)
        val boundaries=sortedSetOf<Instant>()
        if(settings.enabled)settings.rules.filter {it.enabled}.forEach {rule->
            val ruleZone=rule.conditions.zoneId?.let(ZoneId::of)?:zone
            val date=now.atZone(ruleZone).toLocalDate()
            for(offset in -1L..8L) {
                val day=date.plusDays(offset)
                rule.conditions.windows.filter {day.dayOfWeek in it.days}.forEach {window->
                    val fullDay=window.startMinute==window.endMinute
                    val start=day.atStartOfDay().plusMinutes(if(fullDay)0L else window.startMinute.toLong())
                    val end=day.plusDays(if(window.startMinute>=window.endMinute)1L else 0L).atStartOfDay()
                        .plusMinutes(if(fullDay)0L else window.endMinute.toLong())
                    for(local in listOf(start,end))boundaries.addAll(instants(local,ruleZone))
                }
            }
            // Local time can move backwards into a range, or jump over a boundary.
            var transition=ruleZone.rules.nextTransition(now)
            val horizon=now.plusSeconds(9*86400L)
            while(transition!=null&&transition.instant<=horizon) {
                boundaries.add(transition.instant)
                transition=ruleZone.rules.nextTransition(transition.instant.plusNanos(1))
            }
        }
        val next=boundaries.firstOrNull {it>now&&winner(settings,it,zone,environment,profiles)?.id!=selected?.id}
        return Decision(selected,next,selected!=null&&environment.vpnActive&&!selected.action.allowActiveVpnReconfiguration)
    }
    private fun instants(local:LocalDateTime,zone:ZoneId):List<Instant> {
        val offsets=zone.rules.getValidOffsets(local)
        return if(offsets.isEmpty())listOf(zone.rules.getTransition(local).instant)else offsets.map {local.toInstant(it)}
    }
    fun json(settings:Settings):JSONObject=JSONObject().put("version",1).put("enabled",settings.enabled).put("rules",JSONArray().apply {
        settings.rules.forEach {rule->put(JSONObject().put("id",rule.id).put("name",rule.name).put("priority",rule.priority).put("enabled",rule.enabled)
            .put("conditions",JSONObject().put("network",rule.conditions.network.name).put("windows",JSONArray().apply {
                rule.conditions.windows.forEach {window->put(JSONObject().put("days",JSONArray(window.days.sortedBy {it.value}.map {it.value})).put("start",window.startMinute).put("end",window.endMinute))}
            }).apply {
                rule.conditions.zoneId?.let {put("zone",it)};rule.conditions.roaming?.let {put("roaming",it)}
                rule.conditions.charging?.let {put("charging",it)};rule.conditions.batteryAtMost?.let {put("batteryAtMost",it)}
            }).put("action",JSONObject().put("allowActiveVpnReconfiguration",rule.action.allowActiveVpnReconfiguration).apply {
                rule.action.profileId?.let {put("profileId",it)}
                if(rule.action.toggles.isNotEmpty())put("toggles",JSONObject().apply {rule.action.toggles.forEach {(key,value)->put(key.key,value)}})
            }))}
    })
    private fun keys(value:JSONObject,allowed:Set<String>) {require(value.keys().asSequence().all {it in allowed}) {"Неизвестный параметр расписания."}}
    private fun bool(value:JSONObject,key:String):Boolean {require(value.get(key) is Boolean);return value.getBoolean(key)}
    private fun int(value:JSONObject,key:String):Int {require(value.get(key) is Int);return value.getInt(key)}
    private fun string(value:JSONObject,key:String):String {require(value.get(key) is String);return value.getString(key)}
    fun fromJson(value:JSONObject):Settings {
        keys(value,setOf("version","enabled","rules"));require(int(value,"version")==1)
        val rules=value.getJSONArray("rules");require(rules.length()<=32)
        return Settings(bool(value,"enabled"),(0 until rules.length()).map {index->
            val r=rules.getJSONObject(index);keys(r,setOf("id","name","priority","enabled","conditions","action"))
            val c=r.getJSONObject("conditions");keys(c,setOf("network","windows","zone","roaming","charging","batteryAtMost"))
            val windows=c.getJSONArray("windows");require(windows.length()<=16)
            val conditions=Conditions((0 until windows.length()).map {i->
                val w=windows.getJSONObject(i);keys(w,setOf("days","start","end"))
                val days=w.getJSONArray("days");require(days.length() in 1..7)
                Window((0 until days.length()).map {j->require(days.get(j) is Int);DayOfWeek.of(days.getInt(j))}.toSet(),int(w,"start"),int(w,"end"))
            },if(c.has("zone"))string(c,"zone")else null,Network.valueOf(string(c,"network")),
                if(c.has("roaming"))bool(c,"roaming")else null,if(c.has("charging"))bool(c,"charging")else null,
                if(c.has("batteryAtMost"))int(c,"batteryAtMost")else null)
            val a=r.getJSONObject("action");keys(a,setOf("profileId","toggles","allowActiveVpnReconfiguration"))
            val toggles=if(a.has("toggles"))a.getJSONObject("toggles").let {t->
                keys(t,Toggle.entries.map {it.key}.toSet());t.keys().asSequence().associate {key->Toggle.entries.first {it.key==key} to bool(t,key)}
            }else emptyMap()
            Rule(string(r,"id"),string(r,"name"),int(r,"priority"),bool(r,"enabled"),conditions,
                Action(if(a.has("profileId"))string(a,"profileId")else null,toggles,bool(a,"allowActiveVpnReconfiguration")))
        })
    }
}
