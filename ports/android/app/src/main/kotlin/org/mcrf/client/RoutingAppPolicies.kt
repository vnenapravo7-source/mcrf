package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Explicitly confirmed global-policy repair only; never removes individual routing lists. */
object RoutingAppPolicies {
    fun clearGlobal(state:JSONObject) {
        state.put("excludedApps",JSONArray()).put("vpnApps",JSONArray()).put("appMode","all")
    }
}
