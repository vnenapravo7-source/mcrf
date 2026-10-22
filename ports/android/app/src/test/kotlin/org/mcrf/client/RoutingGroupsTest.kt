package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutingGroupsTest {
    private fun rule(id:String)=JSONObject().put("id",id).put("name",id).put("text","example.com").put("mode","addresses").put("target","proxy")
    @Test fun migrationPreservesOriginalRulesAndCreatesThreeEmptyNamedSlots(){
        val state=RouteRules.defaults();state.getJSONArray("rules").put(rule("original"))
        RoutingGroups.ensure(state)
        assertEquals(listOf("VPN","БС","Авто VPN + БС"),RoutingGroups.groups(state).map {it.second})
        assertEquals("vpn",RoutingGroups.active(state));RoutingStore.validate(state)
        RoutingGroups.select(state,"bs");assertEquals(0,state.getJSONArray("rules").length())
        state.getJSONArray("rules").put(rule("bs-only"));RoutingGroups.synchronize(state)
        RoutingGroups.select(state,"vpn");assertEquals("original",state.getJSONArray("rules").getJSONObject(0).getString("id"))
        assertEquals(1,GeoDataConfig.orderedRules(state).length())
        RoutingGroups.select(state,"bs");assertEquals("bs-only",state.getJSONArray("rules").getJSONObject(0).getString("id"))
        RoutingStore.validate(state)
    }
    @Test fun renameDoesNotChangeIdentityOrEnableDetectionAndCycleStaysStable(){
        val state=RouteRules.defaults();RoutingGroups.ensure(state)
        RoutingGroups.rename(state,"bs","Поездка")
        assertEquals("bs",RoutingGroups.next(state));assertFalse(RoutingGroups.select(state,"vpn"))
        RoutingGroups.select(state,"bs");assertEquals("auto",RoutingGroups.next(state))
        RoutingGroups.select(state,"auto");assertEquals("vpn",RoutingGroups.next(state))
        assertFalse(state.has(BsDetectionMonitor.KEY))
        assertEquals("Поездка",RoutingGroups.groups(state)[1].second)
    }
    @Test fun backupValidationRejectsBrokenInactiveGroups(){
        val state=RouteRules.defaults();RoutingGroups.ensure(state)
        state.getJSONObject(RoutingGroups.KEY).getJSONArray("groups").getJSONObject(1).getJSONObject("data").put("defaultTarget","invalid")
        assertThrows(IllegalArgumentException::class.java){RoutingStore.validate(state)}
    }
}
