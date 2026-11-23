package org.mcrf.client

import org.json.JSONObject

data class ClientBehaviorSettings(
    val reconnectOnLaunch:Boolean=true,
    val resetOnNetworkChange:Boolean=true,
    val resetOnWake:Boolean=true,
    val metered:Boolean=false,
    val hideRecents:Boolean=false,
    val autoRecoveryEnabled:Boolean=true,
    val killSwitchBeta:Boolean=KillSwitchPolicy.DEFAULT_ENABLED,
    val adaptivePolicyEnabled:Boolean=false,
    val conserveBattery:Boolean=true,
    val conserveMobile:Boolean=false,
    val conserveRoaming:Boolean=true,
    val prioritizeRealtime:Boolean=false,
    val unstableNetwork:Boolean=false,
    val foregroundPolicyEnabled:Boolean=false,
    val unstableFailures:Int=3,
    val unstableIntervalSeconds:Int=60
) {
    init {require(unstableFailures in 1..10 && unstableIntervalSeconds in 5..3600)}
    val recoveryEnabled get()=reconnectOnLaunch&&autoRecoveryEnabled
    fun withRecovery(enabled:Boolean)=copy(reconnectOnLaunch=enabled,autoRecoveryEnabled=enabled)
    fun json()=JSONObject().put("reconnectOnLaunch",reconnectOnLaunch).put("resetOnNetworkChange",resetOnNetworkChange)
        .put("resetOnWake",resetOnWake).put("metered",metered).put("hideRecents",hideRecents)
        .put("autoRecoveryEnabled",autoRecoveryEnabled).put("killSwitchBeta",killSwitchBeta)
        .put("adaptivePolicyEnabled",adaptivePolicyEnabled).put("conserveBattery",conserveBattery)
        .put("conserveMobile",conserveMobile).put("conserveRoaming",conserveRoaming)
        .put("prioritizeRealtime",prioritizeRealtime).put("unstableNetwork",unstableNetwork).put("foregroundPolicyEnabled",foregroundPolicyEnabled)
        .put("unstableFailures",unstableFailures).put("unstableIntervalSeconds",unstableIntervalSeconds)
    fun shouldReconnect(wanted:Boolean,hasProfile:Boolean,consentReady:Boolean,alreadyActive:Boolean)=
        recoveryEnabled&&wanted&&hasProfile&&consentReady&&!alreadyActive
    fun recoveryEnabled(reason:RecoveryReason)=recoveryEnabled&&
        (reason!=RecoveryReason.NETWORK_LOSS||resetOnNetworkChange)
    companion object {
        const val KEY="generalOptions"
        fun fromJson(value:JSONObject):ClientBehaviorSettings {
            val defaults=ClientBehaviorSettings().json()
            value.keys().forEach {key->require(defaults.has(key)&&
                (if(key in setOf("unstableFailures","unstableIntervalSeconds"))value.get(key) is Int else value.get(key) is Boolean)){
                "Некорректные параметры поведения приложения."}}
            return ClientBehaviorSettings(value.optBoolean("reconnectOnLaunch",true),value.optBoolean("resetOnNetworkChange",true),
                value.optBoolean("resetOnWake",true),value.optBoolean("metered"),value.optBoolean("hideRecents"),
                value.optBoolean("autoRecoveryEnabled",value.optBoolean("reconnectOnLaunch",true)),value.optBoolean("killSwitchBeta",false),
                value.optBoolean("adaptivePolicyEnabled"),value.optBoolean("conserveBattery",true),value.optBoolean("conserveMobile"),
                value.optBoolean("conserveRoaming",true),value.optBoolean("prioritizeRealtime"),value.optBoolean("unstableNetwork"),value.optBoolean("foregroundPolicyEnabled"),
                value.optInt("unstableFailures",3),value.optInt("unstableIntervalSeconds",60))
        }
    }
}
