package org.mcrf.client

import android.content.Context
import org.json.JSONObject

/** Same encrypted generation as the profile references; portable settings never include an approval. */
internal class BehaviorScheduleStore(private val read:()->String,private val write:(String)->Unit) {
    private var context:Context?=null
    constructor(context:Context):this({ProfileStore(context,"preferences.enc").read()},{ProfileStore(context,"preferences.enc").write(it)}) {this.context=context.applicationContext}
    fun load():BehaviorSchedule.Settings=synchronized(ProfileStore.storageLock) {
        val root=JSONObject(read().ifBlank {"{}"})
        root.optJSONObject(BehaviorSchedule.KEY)?.let(BehaviorSchedule::fromJson)?:BehaviorSchedule.Settings()
    }
    fun save(expected:BehaviorSchedule.Settings,next:BehaviorSchedule.Settings)=synchronized(ProfileStore.storageLock) {
        require(load()==expected) {"Расписание изменилось. Откройте его заново."}
        val checked=BehaviorSchedule.fromJson(BehaviorSchedule.json(next))
        val root=JSONObject(read().ifBlank {"{}"})
        val profiles=root.optJSONObject(ConditionProfiles.KEY)?.let(ConditionProfiles::fromJson).orEmpty()
        require(checked.rules.all {it.action.profileId==null||profiles.any {p->p.id==it.action.profileId&&p.configuration!=null}}) {
            "Выбранный сетевой профиль больше не доступен."
        }
        root.put(BehaviorSchedule.KEY,BehaviorSchedule.json(checked));write(root.toString())
        // The encrypted save is already committed. Scheduler rejection must not
        // report a failed save or keep an editor on its obsolete expected state.
        context?.let {runCatching {BehaviorScheduleRecovery.recover(it)}}
        Unit
    }
    companion object {
        fun register()=AndroidBackup.registerSection(BehaviorSchedule.KEY,BackupDefaults.Group.PRESERVE) {BehaviorSchedule.fromJson(it);Unit}
    }
}
