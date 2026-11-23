package org.mcrf.client

import android.content.Context
import org.json.JSONObject

/** Explicit profile application always goes through a detached preview and the shared writer lock. */
internal object ConditionProfilesApply {
    class NetworkPlan(val targetId:String,val section:JSONObject,val preview:ChangePreview)
    /** Update the fallback only: preview cancellation never captures or switches a live profile. */
    fun prepareDefaultUpdate(before:Map<String,String>):ChangePreview {
        val root=JSONObject(before.getValue("preferences.enc").ifBlank {"{}"})
        val section=root.optJSONObject(ConditionProfiles.KEY)?:ConditionProfiles.json(emptyList())
        ConditionProfiles.fromJson(section)
        require(before.getValue("routing.enc").isNotBlank()) {"Сначала сохраните маршруты."}
        val current=NetworkProfileConfiguration.capture(before.getValue("routing.enc"),before.getValue("connections.enc"),root)
        val updated=JSONObject(section.toString()).put("fallback",current.json())
        root.put(ConditionProfiles.KEY,updated)
        return ChangePreview.prepare(before,before+("preferences.enc" to root.toString()))
    }
    fun updateDefault(context:Context,preview:ChangePreview)=synchronized(ProfileStore.storageLock) {
        preview.apply({ProfileStore.historySnapshot(context)},{AndroidBackup.validatePreferences(JSONObject(it.getValue("preferences.enc")))}) {values->
            ProfileStore(context,"preferences.enc").write(values.getValue("preferences.enc"))
        }
        BehaviorScheduleRecovery.recover(context)
    }
    /** Both foreground and recovery paths stage a detached transition before any durable commit. */
    fun prepareNetworkTransition(before:Map<String,String>,network:ConditionEnvironment,safeMode:Boolean=false):ChangePreview? {
        return networkPlan(before,network,safeMode)?.preview
    }
    fun networkPlan(before:Map<String,String>,network:ConditionEnvironment,safeMode:Boolean=false,authoritative:Boolean=false,
        now:java.time.Instant=java.time.Instant.now(),zone:java.time.ZoneId=java.time.ZoneId.systemDefault()):NetworkPlan? {
        if(safeMode)return null
        val prefs=JSONObject(before.getValue("preferences.enc").ifBlank {"{}"})
        val section=prefs.optJSONObject(ConditionProfiles.KEY)?:return null
        if(ConditionProfiles.fromJson(section).isEmpty()&&!section.has("fallback"))return null
        require(before.getValue("routing.enc").isNotBlank()) {"Сначала сохраните текущие настройки маршрутизации."}
        val current=NetworkProfileConfiguration.capture(before.getValue("routing.enc"),before.getValue("connections.enc"),prefs)
        val transition=NetworkProfileSwitch.transition(section,network,current,retainEdits=!authoritative,now=now,zone=zone)
        if(!transition.changed)return null
        val next=transition.target?.let {NetworkProfileSwitch.apply(it,before)}?:before
        val preferences=JSONObject(next.getValue("preferences.enc")).put(ConditionProfiles.KEY,transition.section)
        return NetworkPlan(transition.section.getString("activeId"),transition.section,
            ChangePreview.prepare(before,next+("preferences.enc" to preferences.toString())))
    }
    fun prepare(context:Context,id:String):ChangePreview {
        return synchronized(ProfileStore.storageLock) {
            require(!SafeModeStore(context).enabled()) {"Сетевые профили отключены в безопасном режиме."}
            val before=ProfileStore.historySnapshot(context)
            prepareSelection(before,id)
        }
    }
    fun prepareSelection(before:Map<String,String>,id:String,preserveApproved:Boolean=false):ChangePreview {
        val prefs=JSONObject(before.getValue("preferences.enc").ifBlank {"{}"})
        val section=prefs.getJSONObject(ConditionProfiles.KEY)
        val profiles=ConditionProfiles.fromJson(section)
        val target=profiles.firstOrNull {it.id==id}?:error("Сетевой профиль удалён.")
        val configuration=target.configuration?:error("Сначала сохраните настройки сетевого профиля.")
        val active=section.optString("activeId")
        // Do not invoke RoutingStore.load here: its migration can write even if preview is cancelled.
        require(before.getValue("routing.enc").isNotBlank()) {"Сначала сохраните текущие настройки маршрутизации."}
        val current=NetworkProfileConfiguration.capture(before.getValue("routing.enc"),before.getValue("connections.enc"),prefs)
        // Preserve outgoing edits, but keep the approved target bytes exactly as stored.
        val retained=profiles.map {if(!preserveApproved&&it.id==active&&it.id!=id)it.copy(configuration=current)else it}
        val nextSection=ConditionProfiles.json(retained).put("activeId",id)
            .put("fallback",if(!preserveApproved&&active==NetworkProfileSwitch.DEFAULT)current.json()else section.optJSONObject("fallback")?:current.json())
        val after=NetworkProfileSwitch.apply(configuration,before).toMutableMap()
        after["preferences.enc"]=JSONObject(after.getValue("preferences.enc")).put(ConditionProfiles.KEY,nextSection).toString()
        return ChangePreview.prepare(before,after)
    }
    private fun validate(context:Context,values:Map<String,String>) {
        PresetAssets.validate(context,JSONObject(values.getValue("routing.enc")))
        val catalogue=ConnectionCatalogue(values.getValue("connections.enc"))
        catalogue.serialize()
        val ids=catalogue.entries().map {it.id}.toSet()
        val prefs=JSONObject(values.getValue("preferences.enc"))
        listOf("selected","bsLastManualVpn","bsLastManualBs").forEach {key->require(prefs.optString(key).let {it.isEmpty()||it in ids}) {"Сохранённый сервер недоступен."}}
        AndroidBackup.validatePreferences(JSONObject(values.getValue("preferences.enc")))
    }
    fun vpnActive()=McrfVpnService.hasActiveSession()||McrfVpnService.wifiPaused()||McrfVpnService.cleanupPending()||
        McrfVpnService.status in setOf("Подключение…","Восстановление соединения…","Возвращаемся в VPN…")||
        McrfVpnService.status.startsWith("Переключаем")
    fun apply(context:Context,preview:ChangePreview,allowActiveVpnReconfiguration:Boolean=false,
              evidenceCurrent:()->Boolean={true})=synchronized(ProfileStore.storageLock) {
        fun guard() {
            require(!SafeModeStore(context).enabled()) {"Включён безопасный режим."}
            require(!vpnActive()||allowActiveVpnReconfiguration) {"Действие отложено до отключения VPN."}
            require(evidenceCurrent()) {"Условия изменились. Откройте предпросмотр заново."}
        }
        guard()
        preview.apply({ProfileStore.historySnapshot(context)},{validate(context,it);guard()}) {values->
            guard()
            require(ChangeHistoryStore.local(context).checkpoint(ProfileStore.historySnapshot(context))) {"Недостаточно места для точки восстановления."}
            val generation=ProfileStore.withoutHistory {ProfileStore.replaceAll(context,values)}
            val preferences=JSONObject(values.getValue("preferences.enc"))
            val editor=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit()
            NetworkProfileConfiguration.keys.forEach {key->
                editor.remove(key)
                if(preferences.has(key))when(val value=preferences.get(key)) {
                    is Boolean->editor.putBoolean(key,value);is Int->editor.putInt(key,value);is String->editor.putString(key,value)
                }
            }
            check(editor.putString("restoreAppliedGeneration",generation).commit())
        }
        NetworkProfilesRuntime.configurationApplied()
        if(allowActiveVpnReconfiguration&&vpnActive())McrfVpnService.networkProfileChanged(context)
    }
}
