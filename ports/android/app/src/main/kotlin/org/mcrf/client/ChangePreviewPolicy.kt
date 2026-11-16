package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Device-local approval receipts contain only hashes, never settings or credentials. Not portable backup data. */
internal object ChangePreviewPolicy {
    private const val STORE="change-preview-policy"
    class Review internal constructor(val preview:ChangePreview,internal val tokens:Set<String>)
    class Lease internal constructor(val revision:String,val tokens:Set<String>,val allowActiveNetwork:Boolean) {
        fun current(context:Context)=preferences(context).getString("revision","")==revision
        fun permitsProfile(section:JSONObject,id:String)=tokens.isNotEmpty()&&profileToken(section,id)?.let {it in tokens}==true
        fun permitsRule(rule:BehaviorSchedule.Rule,section:JSONObject?)=tokens.isNotEmpty()&&ruleToken(rule,section)?.let {it in tokens}==true
    }
    private fun preferences(context:Context)=context.getSharedPreferences(STORE,Context.MODE_PRIVATE)
    fun lease(context:Context):Lease {
        val source=preferences(context)
        val enabled=source.getBoolean("enabled",source.getStringSet("approved",emptySet()).orEmpty().isNotEmpty())
        return Lease(source.getString("revision","").orEmpty(),if(enabled)source.getStringSet("approved",emptySet()).orEmpty().toSet() else emptySet(),source.getBoolean("allowActiveNetwork",false))
    }
    fun enabled(context:Context)=preferences(context).getBoolean("enabled",preferences(context).getStringSet("approved",emptySet()).orEmpty().isNotEmpty())
    fun disable(context:Context) {
        check(preferences(context).edit().putBoolean("enabled",false).remove("approved")
            .putString("revision",UUID.randomUUID().toString()).commit()) {"Не удалось выключить автоприменение."}
        ProfilePolicyRuntime.invalidate()
        McrfVpnService.networkProfileChanged(context)
        BehaviorScheduleRuntime.refresh(context)
    }
    private fun canonical(value:Any?):String=when(value) {
        is JSONObject->value.keys().asSequence().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonical(value.get(it))}
        is String->JSONObject.quote(value)
        else->value.toString()
    }
    private fun hash(kind:String,value:JSONObject)=kind+":"+MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    internal fun profileToken(section:JSONObject,id:String):String? {
        val profiles=ConditionProfiles.fromJson(section)
        val value=if(id==NetworkProfileSwitch.DEFAULT)section.optJSONObject("fallback")?.let {JSONObject().put("id",id).put("configuration",it)}
            else profiles.firstOrNull {it.id==id&&it.configuration!=null}?.json()
        return value?.let {hash("profile",it)}
    }
    internal fun ruleToken(rule:BehaviorSchedule.Rule,section:JSONObject?):String? {
        val profile=rule.action.profileId?.let {id->section?.let {profileToken(it,id)}?:return null}
        val value=BehaviorSchedule.json(BehaviorSchedule.Settings(true,listOf(rule))).getJSONArray("rules").getJSONObject(0)
        profile?.let {value.put("approvedProfile",it)}
        return hash("rule",value)
    }
    internal fun tokens(values:Map<String,String>):Set<String> {
        val root=JSONObject(values.getValue("preferences.enc").ifBlank {"{}"})
        val section=root.optJSONObject(ConditionProfiles.KEY)
        val result=linkedSetOf<String>()
        section?.let {
            ConditionProfiles.fromJson(it).forEach {p->profileToken(it,p.id)?.let(result::add)}
            profileToken(it,NetworkProfileSwitch.DEFAULT)?.let(result::add)
        }
        root.optJSONObject(BehaviorSchedule.KEY)?.let {BehaviorSchedule.fromJson(it).rules.forEach {r->ruleToken(r,section)?.let(result::add)}}
        return result.toSet()
    }
    fun prepare(context:Context):Review=synchronized(ProfileStore.storageLock) {
        val before=ProfileStore.historySnapshot(context)
        val root=JSONObject(before.getValue("preferences.enc").ifBlank {"{}"})
        val targets=mutableListOf<Map<String,String>>()
        val notes=mutableListOf<String>()
        root.optJSONObject(ConditionProfiles.KEY)?.let {section->
            val profiles=ConditionProfiles.fromJson(section)
            if(profiles.isNotEmpty()||section.has("fallback")) {
                require(before.getValue("routing.enc").isNotBlank()) {"Сначала сохраните текущие настройки маршрутизации."}
                val current=NetworkProfileConfiguration.capture(before.getValue("routing.enc"),before.getValue("connections.enc"),root)
                val initialized=profiles.map {if(it.configuration==null)it.copy(configuration=current)else it}
                initialized.forEachIndexed {i,p->notes.add("Сетевой профиль ${i+1} · ${p.condition.title} · сохранённый снимок")}
                notes.add("Профиль по умолчанию · сохранённый снимок")
                val next=ConditionProfiles.json(initialized).put("fallback",section.optJSONObject("fallback")?:current.json())
                if(section.has("activeId"))next.put("activeId",section.getString("activeId"))
                root.put(ConditionProfiles.KEY,next)
                (initialized.mapNotNull {it.configuration}+NetworkProfileConfiguration.fromJson(next.getJSONObject("fallback"))).forEach {
                    targets.add(NetworkProfileSwitch.apply(it,before))
                }
            }
        }
        root.optJSONObject(BehaviorSchedule.KEY)?.let {BehaviorSchedule.fromJson(it).rules.forEachIndexed {index,rule->
            notes.add("Правило ${index+1} · ${rule.conditions.network.title} · временных диапазонов: ${rule.conditions.windows.size}")
            notes.add("Правило ${index+1} · "+(if(rule.action.profileId!=null)"выбрать сохранённый сетевой профиль"else
                rule.action.toggles.entries.sortedBy {it.key.name}.joinToString {it.key.title+if(it.value)" · вкл"else" · выкл"}))
            notes.add("Правило ${index+1} · "+if(rule.action.allowActiveVpnReconfiguration)"разрешена перенастройка активного VPN"else"ждать отключения VPN")
        }}
        val after=before+("preferences.enc" to root.toString())
        Review(ChangePreview.preparePolicy(before,after,targets,notes),tokens(after))
    }
    fun approve(context:Context,review:Review,allowActiveNetwork:Boolean)=synchronized(ProfileStore.storageLock) {
        review.preview.apply({ProfileStore.historySnapshot(context)},{AndroidBackup.validatePreferences(JSONObject(it.getValue("preferences.enc")))}) {values->
            require(tokens(values)==review.tokens)
            val source=values.getValue("preferences.enc")
            if(ProfileStore(context,"preferences.enc").read()!=source)ProfileStore(context,"preferences.enc").write(source)
            BehaviorScheduleRecovery.recover(context)
            // Write approval LAST. A crash before this point leaves every new snapshot unapproved.
            check(preferences(context).edit().putBoolean("enabled",true).putStringSet("approved",review.tokens.toSet()).putBoolean("allowActiveNetwork",allowActiveNetwork)
                .putString("revision",UUID.randomUUID().toString()).commit()) {"Не удалось сохранить подтверждение автополитики."}
        }
        ProfilePolicyRuntime.invalidate()
        if(allowActiveNetwork&&ProfilePolicyRuntime.active(context)!=null)McrfVpnService.networkProfileChanged(context)
        BehaviorScheduleRuntime.refresh(context)
    }
}
