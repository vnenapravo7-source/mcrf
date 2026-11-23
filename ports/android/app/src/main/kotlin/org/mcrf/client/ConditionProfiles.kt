package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

enum class ProfileCondition(val title:String) { WIFI("Wi-Fi"), SSID("Имя Wi-Fi (SSID)"), CELLULAR("Мобильная сеть"), ROAMING("Роуминг") }

/** Local wall-clock range; overnight ranges belong to their starting day. */
data class ProfileTimeWindow(val days:Set<Int>,val startMinute:Int,val endMinute:Int) {
    init {require(days.isNotEmpty()&&days.all {it in 1..7}&&startMinute in 0..1439&&endMinute in 0..1439)}
    fun matches(now:ZonedDateTime):Boolean {
        val minute=now.hour*60+now.minute
        val day=if(startMinute>endMinute&&minute<endMinute)now.minusDays(1).dayOfWeek.value else now.dayOfWeek.value
        return day in days&&when {
            startMinute==endMinute->true
            startMinute<endMinute->minute>=startMinute&&minute<endMinute
            else->minute>=startMinute||minute<endMinute
        }
    }
    fun json()=JSONObject().put("days",JSONArray(days.sorted())).put("start",startMinute).put("end",endMinute)
}

data class ConditionProfile(
    val id:String, val name:String, val condition:ProfileCondition,
    val priority:Int=0, val ssid:String?=null, val mode:ExperienceMode=ExperienceMode.SIMPLE,
    val enabled:Boolean=true, val configuration:NetworkProfileConfiguration?=null,
    val networkTrigger:Boolean=true, val windows:List<ProfileTimeWindow> = emptyList()
) {
    fun json():JSONObject=JSONObject().put("id",id).put("name",name).put("condition",condition.name)
        .put("priority",priority).put("mode",mode.id).put("enabled",enabled).apply {
            ssid?.let {put("ssid",it)};configuration?.let {put("configuration",it.json())}
            // Omit legacy defaults to preserve existing device-local approval hashes.
            if(!networkTrigger)put("networkTrigger",false)
            if(windows.isNotEmpty())put("windows",JSONArray(windows.map {it.json()}))
        }
}

/** Fresh physical-network evidence only. Unknown SSID/roaming never counts as a match. */
data class ConditionEnvironment(
    val wifi:Boolean=false,val cellular:Boolean=false,val roaming:Boolean?=null,
    val ssids:Set<String> = emptySet(),val locationAllowed:Boolean=false
)

object ConditionProfiles {
    const val KEY="conditionProfiles"
    const val TITLE="Поведение MCRF в разных сетях"
    const val PRIORITY_COPY="Выше число — выше приоритет. При равенстве: профиль с расписанием, затем с условием сети; SSID → роуминг → Wi-Fi → мобильная сеть; затем ID. Применяется один профиль."
    fun ordered(profiles:List<ConditionProfile>)=profiles.sortedWith(compareByDescending<ConditionProfile> {it.priority}
        .thenByDescending {it.windows.isNotEmpty()}.thenByDescending {it.networkTrigger}
        .thenByDescending {when(it.condition){ProfileCondition.SSID->4;ProfileCondition.ROAMING->3;ProfileCondition.WIFI->2;ProfileCondition.CELLULAR->1}}
        .thenBy {it.id})
    fun matchesNetwork(profile:ConditionProfile,network:ConditionEnvironment):Boolean= !profile.networkTrigger||when(profile.condition) {
        ProfileCondition.WIFI->network.wifi
        ProfileCondition.SSID->network.wifi&&network.locationAllowed&&profile.ssid!=null&&profile.ssid in network.ssids
        ProfileCondition.CELLULAR->network.cellular
        ProfileCondition.ROAMING->network.cellular&&network.roaming==true
    }
    fun resolve(profiles:List<ConditionProfile>,network:ConditionEnvironment,safeMode:Boolean,
                now:Instant=Instant.now(),zone:ZoneId=ZoneId.systemDefault()):ConditionProfile? {
        if(safeMode)return null
        return ordered(profiles.filter {it.enabled&&matchesNetwork(it,network)&&
            (it.windows.isEmpty()||it.windows.any {window->window.matches(now.atZone(zone))})}).firstOrNull()
    }
    fun json(profiles:List<ConditionProfile>)=JSONObject().put("profiles",JSONArray(profiles.map {it.json()}))
    fun fromJson(value:JSONObject):List<ConditionProfile> {
        require(value.keys().asSequence().all {it in setOf("profiles","activeId","fallback")}) {"Неизвестные параметры профилей."}
        if(value.has("activeId"))require(value.get("activeId") is String)
        if(value.has("fallback"))NetworkProfileConfiguration.fromJson(value.getJSONObject("fallback"))
        val array=value.getJSONArray("profiles")
        require(array.length()<=32) {"Не более 32 профилей."}
        val profiles=(0 until array.length()).map {index->
            val p=array.getJSONObject(index)
            require(p.keys().asSequence().all {it in setOf("id","name","condition","priority","ssid","mode","enabled","configuration","networkTrigger","windows")})
            for(key in listOf("id","name","condition","mode"))require(p.get(key) is String)
            require(p.get("priority") is Int&&p.get("enabled") is Boolean)
            require(!p.has("ssid")||p.get("ssid") is String)
            val condition=ProfileCondition.valueOf(p.getString("condition"))
            val ssid=if(p.has("ssid"))p.getString("ssid")else null
            val mode=requireNotNull(ExperienceMode.entries.firstOrNull {it.id==p.getString("mode")}) {"Неизвестный режим интерфейса."}
            require(!p.has("networkTrigger")||p.get("networkTrigger") is Boolean)
            val windows=if(!p.has("windows"))emptyList()else p.getJSONArray("windows").let {array->
                require(array.length() in 1..16)
                (0 until array.length()).map {i->
                    val window=array.getJSONObject(i)
                    require(window.keys().asSequence().toSet()==setOf("days","start","end"))
                    require(window.get("start") is Int&&window.get("end") is Int)
                    val days=window.getJSONArray("days");require(days.length() in 1..7)
                    ProfileTimeWindow((0 until days.length()).map {j->require(days.get(j) is Int);days.getInt(j)}.toSet(),window.getInt("start"),window.getInt("end"))
                }
            }
            ConditionProfile(p.getString("id"),p.getString("name"),condition,p.getInt("priority"),ssid,mode,p.getBoolean("enabled"),
                if(p.has("configuration"))NetworkProfileConfiguration.fromJson(p.getJSONObject("configuration"))else null,
                p.optBoolean("networkTrigger",true),windows).also {
                require(it.networkTrigger||it.windows.isNotEmpty()) {"Выберите сеть или расписание."}
                require(it.id.matches(Regex("[A-Za-z0-9-]{1,64}"))&&it.id!=NetworkProfileSwitch.DEFAULT)
                require(it.name.isNotBlank()&&it.name.length<=80&&it.name.none(Char::isISOControl))
                require(it.priority in 0..1000)
                require(if(condition==ProfileCondition.SSID)ssid!=null&&ssid.isNotBlank()&&ssid!="<unknown ssid>"&&
                    ssid.toByteArray(Charsets.UTF_8).size<=32&&ssid.none(Char::isISOControl) else ssid==null) {"Укажи точное имя Wi-Fi длиной до 32 байт."}
            }
        }
        require(profiles.map {it.id}.distinct().size==profiles.size) {"Повторяющиеся ID профилей."}
        return profiles
    }
}

/** Stored inside the existing encrypted preferences generation and included in backups. */
class ConditionProfilesStore internal constructor(private val read:()->String,private val write:(String)->Unit) {
    private var context:Context?=null
    constructor(context:Context):this(
        {ProfileStore(context,"preferences.enc").read()},
        {ProfileStore(context,"preferences.enc").write(it)}
    ) {this.context=context.applicationContext}
    fun load():List<ConditionProfile> = synchronized(ProfileStore.storageLock) {
        val source=read()
        val root=if(source.isBlank())JSONObject()else JSONObject(source)
        if(root.has(ConditionProfiles.KEY))ConditionProfiles.fromJson(root.getJSONObject(ConditionProfiles.KEY))else emptyList()
    }
    fun save(profiles:List<ConditionProfile>)=synchronized(ProfileStore.storageLock) {
        val section=ConditionProfiles.json(profiles)
        ConditionProfiles.fromJson(section)
        val source=read()
        val root=if(source.isBlank())JSONObject()else JSONObject(source)
        root.optJSONObject(ConditionProfiles.KEY)?.let {old->
            for(key in listOf("activeId","fallback"))if(old.has(key))section.put(key,old.get(key))
        }
        root.put(ConditionProfiles.KEY,section)
        write(root.toString())
        context?.let(BehaviorScheduleRecovery::recover)
    }
    fun saveIfUnchanged(expected:List<ConditionProfile>,next:List<ConditionProfile>)=synchronized(ProfileStore.storageLock) {
        require(load()==expected) {"Профили изменились. Обновите список и повторите изменение."}
        save(next)
    }
}
