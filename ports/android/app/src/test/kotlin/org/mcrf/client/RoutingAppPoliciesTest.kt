package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutingAppPoliciesTest {
    @Test fun explicitRepairOnlyClearsThreeGlobalFieldsAndIsIdempotent() {
        val state=JSONObject("""{"excludedApps":["org.example.direct"],"vpnApps":["org.example.vpn"],"appMode":"include","rules":[{"mode":"apps","text":"app:org.example.saved"}],"defaultTarget":"block","globalDns":{"provider":"Google"},"future":{"keep":true}}""")
        val original=JSONObject(state.toString())
        RoutingAppPolicies.clearGlobal(state)
        assertEquals(0,state.getJSONArray("excludedApps").length())
        assertEquals(0,state.getJSONArray("vpnApps").length())
        assertEquals("all",state.getString("appMode"))
        for(key in original.keys())if(key !in setOf("excludedApps","vpnApps","appMode"))assertEquals(original.get(key).toString(),state.get(key).toString())
        val after=state.toString();RoutingAppPolicies.clearGlobal(state);assertEquals(after,state.toString())
        assertEquals(1,original.getJSONArray("excludedApps").length())
    }
}
