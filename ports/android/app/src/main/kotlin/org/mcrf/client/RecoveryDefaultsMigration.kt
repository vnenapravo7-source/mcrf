package org.mcrf.client

import org.json.JSONObject

/** One upgrade enables recovery; later explicit opt-outs remain authoritative. */
internal object RecoveryDefaultsMigration {
    const val KEY="recoveryDefaultsMigration"
    fun apply(root:JSONObject):Boolean {
        if(root.optInt(KEY,0)>=1)return false
        val settings=root.optJSONObject(ClientBehaviorSettings.KEY)?:JSONObject()
        settings.put("reconnectOnLaunch",true).put("autoRecoveryEnabled",true)
            .put("resetOnNetworkChange",true).put("resetOnWake",true)
        root.put(ClientBehaviorSettings.KEY,settings).put(KEY,1)
        return true
    }
}
