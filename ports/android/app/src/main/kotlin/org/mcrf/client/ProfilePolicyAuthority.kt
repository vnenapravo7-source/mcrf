package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Read policy, not a periodic writer. Only an applied, currently matching approved winner owns it. */
internal object ProfilePolicyAuthority {
    data class Policy(val id:String,val configuration:NetworkProfileConfiguration?,val values:Map<String,Any>) {
        fun routing(ordinary:()->JSONObject):JSONObject {
            val source=configuration?.routing?:return ordinary()
            return JSONObject(source).apply {RoutingGroups.synchronize(this);TextGeoRules.compile(this)}
        }
        fun pool(existing:Set<String>,ordinary:Set<String>):Set<String> {
            val source=configuration?.pool?:return ordinary
            val ids=JSONArray(source).let {a->(0 until a.length()).map {a.getString(it)}}
            return if(ids.isEmpty())existing else ids.filterTo(linkedSetOf()) {it in existing}
        }
    }
    class Snapshot internal constructor(
        private val section:JSONObject?,private val profiles:List<ConditionProfile>,
        private val schedule:BehaviorSchedule.Settings,private val tokens:Set<String>
    ) {
        private val networkApprovals=profiles.mapNotNull {p->
            section?.let {ChangePreviewPolicy.profileToken(it,p.id)}?.takeIf {it in tokens}?.let {p.id to it}
        }.toMap()
        private val ruleApprovals=schedule.rules.mapNotNull {rule->
            ChangePreviewPolicy.ruleToken(rule,section)?.takeIf {it in tokens}?.let {rule.id to it}
        }.toMap()
        private val preferences=profiles.associate {p->p.id to p.configuration?.preferences?.let {source->
            val value=JSONObject(source);value.keys().asSequence().associateWith {value.get(it)}
        }.orEmpty()}
        fun resolve(environment:BehaviorSchedule.Environment,now:Instant,zone:ZoneId):Policy? {
            val rule=if(!schedule.enabled)null else schedule.rules.filter {
                it.enabled&&(it.action.profileId==null||profiles.any {p->p.id==it.action.profileId&&p.enabled&&p.configuration!=null})&&
                    it.conditions.matches(now,zone,environment)
            }.sortedWith(compareByDescending<BehaviorSchedule.Rule>{it.priority}.thenBy{it.id}).firstOrNull()
            if(rule!=null) {
                val token=ruleApprovals[rule.id]?:return null
                val target=rule.action.profileId
                if(target==null)return Policy(token,null,rule.action.toggles.mapKeys {it.key.key})
                return profilePolicy(profiles.firstOrNull {it.id==target},token)
            }
            val selected=ConditionProfiles.resolve(profiles,environment.network,false,now,zone)?:return null
            val token=networkApprovals[selected.id]?:return null
            return profilePolicy(selected,token)
        }
        private fun profilePolicy(profile:ConditionProfile?,token:String):Policy? {
            if(profile==null||!profile.enabled||section?.optString("activeId")!=profile.id)return null
            val configuration=profile.configuration?:return null
            return Policy(token,configuration,preferences.getValue(profile.id))
        }
    }
    fun snapshot(preferences:JSONObject,tokens:Set<String>):Snapshot {
        val section=preferences.optJSONObject(ConditionProfiles.KEY)?.let {JSONObject(it.toString())}
        return Snapshot(section,section?.let(ConditionProfiles::fromJson).orEmpty(),
            preferences.optJSONObject(BehaviorSchedule.KEY)?.let(BehaviorSchedule::fromJson)?:BehaviorSchedule.Settings(),tokens.toSet())
    }
}
