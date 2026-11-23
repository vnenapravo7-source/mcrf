package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyRoutingMigrationTest {
    private val country="ip_cidr: 192.0.2.0/24"
    private fun legacy()=RouteRules.defaults().put("defaultTarget","direct").apply {
        getJSONArray("rules").put(RouteRules.outsideRussia(country))
    }
    @Test fun exactLegacyTemplateMigratesOnceWithOrWithoutGroups(){
        for(grouped in listOf(false,true)){
            val state=legacy();if(grouped){RoutingGroups.ensure(state);RoutingGroups.synchronize(state)}
            assertTrue(LegacyRoutingMigration.apply(state,country))
            assertEquals("proxy",state.getString("defaultTarget"))
            state.put("defaultTarget","direct")
            assertFalse(LegacyRoutingMigration.apply(state,country))
            assertEquals("direct",state.getString("defaultTarget"))
            assertFalse(RoutingGroups.runtime(state).has(LegacyRoutingMigration.KEY))
        }
    }
    @Test fun customizedDirectPoliciesArePreserved(){
        val mutations=listOf<(JSONObject)->Unit>(
            {it.put("rules",JSONArray())},
            {it.getJSONArray("rules").getJSONObject(0).put("enabled",false)},
            {it.getJSONArray("rules").getJSONObject(0).put("serverId","saved")},
            {it.getJSONArray("rules").getJSONObject(0).put("text","changed")},
            {it.getJSONArray("excludedApps").put("org.example.app")},
            {it.put("appMode","include")},
            {it.put("connectionBinding",JSONObject().put("enabled",true))},
            {RoutingGroups.ensure(it);RoutingGroups.select(it,"bs")},
            {RoutingGroups.ensure(it);RoutingGroups.rename(it,"vpn","Personal")})
        for(mutate in mutations){
            val state=legacy();mutate(state)
            val before=state.toString()
            LegacyRoutingMigration.apply(state,country)
            state.remove(LegacyRoutingMigration.KEY)
            assertEquals(before,state.toString())
        }
    }
}
