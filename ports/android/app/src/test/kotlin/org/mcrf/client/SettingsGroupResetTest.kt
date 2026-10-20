package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Prepared with item41; intentionally not executed while the human batch hold is active. */
class SettingsGroupResetTest {
    @Test fun resetOwnsOnlyNamedKeysAndDoesNotMutateTheInput(){
        val current=JSONObject().put("mtu",1500).put("shareLan",true).put("token","preserve").put("nested",JSONObject().put("x",1))
        val defaults=JSONObject().put("mtu",1400).put("shareLan",false)
        val reset=SettingsGroupReset.apply(current,defaults,setOf("mtu"))
        assertEquals(1400,reset.getInt("mtu"));assertTrue(reset.getBoolean("shareLan"))
        assertEquals("preserve",reset.getString("token"));assertEquals(1500,current.getInt("mtu"))
        reset.getJSONObject("nested").put("x",2);assertEquals(1,current.getJSONObject("nested").getInt("x"))
    }
    @Test fun neighbourFormSavePreservesSharingChange(){
        val opened=ClientTunSettings().json();val current=JSONObject(opened.toString()).put("shareLan",true)
        val edited=JSONObject(opened.toString()).put("mixedPort",10818)
        val saved=RoutingDefaultsEdits.merge(current,opened,edited)
        assertTrue(saved.getBoolean("shareLan"));assertEquals(10818,saved.getInt("mixedPort"))
    }
    @Test fun dnsResetKeepsResolversAndOtherPolicies(){
        val state=AdvancedDnsConfig.defaults().put("timeout","30s").put("answers","192.0.2.1 example.test")
        val reset=SettingsGroupReset.apply(state,AdvancedDnsConfig.defaults(),setOf("timeout"))
        assertEquals("10s",reset.getString("timeout"));assertEquals(state.getString("answers"),reset.getString("answers"))
        assertTrue(RoutingDefaultsEdits.equivalent(state.getJSONObject("direct"),reset.getJSONObject("direct")))
    }
    @Test fun geoResetDoesNotDropCategoriesOrSwitchSources(){
        val state=GeoDataConfig.defaults().put("source","custom").put("geoipUrl","https://example.test/geo.dat")
        state.getJSONArray("selectors").put(JSONObject().put("kind","geoip").put("code","ru").put("target","direct"))
        val reset=SettingsGroupReset.apply(state,GeoDataConfig.defaults(),setOf("geoipUrl","geositeUrl"))
        assertEquals("custom",reset.getString("source"));assertEquals(state.getJSONArray("selectors").toString(),reset.getJSONArray("selectors").toString())
    }
    @Test(expected=IllegalArgumentException::class) fun unknownResetKeysAreRejected(){SettingsGroupReset.apply(JSONObject(),JSONObject(),setOf("unknown"))}
}
