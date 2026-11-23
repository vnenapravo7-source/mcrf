package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportedRoutingGroupsTest {
    private fun profile()=JSONObject().put("rules",org.json.JSONArray()).put("defaultTarget","proxy")
        .put("globalDns",JSONObject().put("provider","Google").put("protocol","DoH"))
        .put("advancedDns",AdvancedDnsConfig.defaults()).put("geoData",GeoDataConfig.defaults())
    @Test fun importedDnsIsIsolatedAndOriginalRestored() {
        val state=RouteRules.defaults();val original=state.getJSONObject("globalDns").toString()
        val id=RoutingGroups.add(state,"RoscomVPN",profile())
        RoutingGroups.select(state,id);RoutingStore.validate(state)
        assertEquals("Google",state.getJSONObject("globalDns").getString("provider"))
        assertEquals(id,state.getJSONObject("geoData").getString("cacheNamespace"))
        state.getJSONObject("globalDns").put("provider","Quad9")
        RoutingGroups.select(state,"vpn");RoutingStore.validate(state)
        assertEquals(original,state.getJSONObject("globalDns").toString())
        RoutingGroups.select(state,id);assertEquals("Quad9",state.getJSONObject("globalDns").getString("provider"))
    }
    @Test fun sharedDnsStillFollowsOrdinaryGroupChanges() {
        val state=RouteRules.defaults();RoutingGroups.ensure(state)
        state.getJSONObject("globalDns").put("provider","Quad9")
        RoutingGroups.select(state,"bs")
        assertEquals("Quad9",state.getJSONObject("globalDns").getString("provider"))
    }
    @Test fun experienceBankRoundtripRestoresBothDnsOwners() {
        val state=RouteRules.defaults();val original=state.getJSONObject("globalDns").toString()
        val id=RoutingGroups.add(state,"Imported",profile());RoutingGroups.select(state,id)
        ExperienceRouting.select(state,ExperienceMode.SIMPLE);RoutingStore.validate(state)
        assertEquals(original,state.getJSONObject("globalDns").toString())
        ExperienceRouting.select(state,ExperienceMode.EXPERT);RoutingStore.validate(state)
        assertEquals(id,RoutingGroups.active(state))
        assertEquals("Google",state.getJSONObject("globalDns").getString("provider"))
    }
    @Test fun removingActiveImportRestoresOriginalDnsAndBuiltinsSurvive() {
        val state=RouteRules.defaults();val original=state.getJSONObject("globalDns").toString()
        val id=RoutingGroups.add(state,"Imported",profile());RoutingGroups.select(state,id)
        RoutingGroups.remove(state,id);RoutingStore.validate(state)
        assertEquals("vpn",RoutingGroups.active(state));assertEquals(3,RoutingGroups.groups(state).size)
        assertEquals(original,state.getJSONObject("globalDns").toString())
        assertThrows(IllegalArgumentException::class.java){RoutingGroups.remove(state,"vpn")}
    }
    @Test fun namespaceDoesNotAliasStandardOrOtherProfile() {
        val a=GeoDataConfig.defaults();val b=JSONObject(a.toString()).put("cacheNamespace","profile-"+"a".repeat(32))
        val c=JSONObject(a.toString()).put("cacheNamespace","profile-"+"b".repeat(32))
        assertNotEquals(GeoDataConfig.sourceName(a,"geoip"),GeoDataConfig.sourceName(b,"geoip"))
        assertNotEquals(GeoDataConfig.sourceName(b,"geoip"),GeoDataConfig.sourceName(c,"geoip"))
        b.put("cacheNamespace","../escape")
        assertThrows(IllegalArgumentException::class.java){GeoDataConfig.sourceName(b,"geoip")}
    }
}
