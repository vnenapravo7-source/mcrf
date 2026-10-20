package org.mcrf.client

import org.json.JSONObject

data class ClientBehaviorSettings(
    val reconnectOnLaunch:Boolean=true,
    val resetOnNetworkChange:Boolean=true,
    val resetOnWake:Boolean=true,
    val metered:Boolean=false,
    val hideRecents:Boolean=false
) {
    fun json()=JSONObject().put("reconnectOnLaunch",reconnectOnLaunch).put("resetOnNetworkChange",resetOnNetworkChange)
        .put("resetOnWake",resetOnWake).put("metered",metered).put("hideRecents",hideRecents)
    fun shouldReconnect(wanted:Boolean,hasProfile:Boolean,consentReady:Boolean,alreadyActive:Boolean)=
        reconnectOnLaunch&&wanted&&hasProfile&&consentReady&&!alreadyActive
    companion object {
        const val KEY="generalOptions"
        fun fromJson(value:JSONObject):ClientBehaviorSettings {
            val defaults=ClientBehaviorSettings().json()
            value.keys().forEach {require(defaults.has(it)&&value.get(it) is Boolean){"Некорректные параметры поведения приложения."}}
            return ClientBehaviorSettings(value.optBoolean("reconnectOnLaunch",true),value.optBoolean("resetOnNetworkChange",true),
                value.optBoolean("resetOnWake",true),value.optBoolean("metered"),value.optBoolean("hideRecents"))
        }
    }
}
