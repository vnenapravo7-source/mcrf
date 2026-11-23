package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConditionProfilesPreviewTest {
    private fun configuration(target:String)=NetworkProfileConfiguration(
        RouteRules.defaults().put("defaultTarget",target).toString(),"[]","{\"automatic\":false}")
    private fun values():Map<String,String> {
        val current=configuration("proxy");val target=configuration("block")
        val section=ConditionProfiles.json(listOf(ConditionProfile("wifi","PRIVATE_PROFILE_NAME",ProfileCondition.WIFI,configuration=target)))
            .put("activeId",NetworkProfileSwitch.DEFAULT).put("fallback",current.json())
        return mapOf("routing.enc" to current.routing,"connections.enc" to JSONObject().put("servers",JSONArray()).put("subscriptions",JSONArray()).toString(),
            "profile.enc" to "PRIVATE_SECRET","preferences.enc" to JSONObject().put("automatic",false).put(ConditionProfiles.KEY,section).toString())
    }
    @Test fun networkTransitionIsReadOnlyUntilApprovalAndUsesThePreviewSnapshot() {
        val current=values();val saved=current.toMap()
        val preview=requireNotNull(ConditionProfilesApply.prepareNetworkTransition(current,ConditionEnvironment(wifi=true)))
        assertEquals(saved,current)
        assertFalse(preview.groups.toString().contains("PRIVATE"))
        var applied=current
        preview.apply({current},{},{applied=it})
        assertEquals("block",JSONObject(applied.getValue("routing.enc")).getString("defaultTarget"))
        assertEquals("PRIVATE_SECRET",applied.getValue("profile.enc"))
        assertEquals("wifi",JSONObject(applied.getValue("preferences.enc")).getJSONObject(ConditionProfiles.KEY).getString("activeId"))
        assertNull(ConditionProfilesApply.prepareNetworkTransition(applied,ConditionEnvironment(wifi=true)))
        val fallback=requireNotNull(ConditionProfilesApply.prepareNetworkTransition(applied,ConditionEnvironment()))
        fallback.apply({applied},{},{next->assertEquals("proxy",JSONObject(next.getValue("routing.enc")).getString("defaultTarget"))})
    }
    @Test fun safeModeAndStaleConfigurationCannotApplyNetworkTransitions() {
        val current=values()
        assertNull(ConditionProfilesApply.prepareNetworkTransition(current,ConditionEnvironment(wifi=true),true))
        val preview=requireNotNull(ConditionProfilesApply.prepareNetworkTransition(current,ConditionEnvironment(wifi=true)))
        val stale=current+("profile.enc" to "PRIVATE_NEW_SECRET")
        assertThrows(IllegalArgumentException::class.java){preview.apply({stale},{},{fail("stale network preview committed")})}
    }
    @Test fun defaultUpdateIsDetachedPreservesProfilesAndRejectsStaleState() {
        val base=values()
        val before=base+("preferences.enc" to JSONObject(base.getValue("preferences.enc")).put("automatic",true).toString())
        val preview=ConditionProfilesApply.prepareDefaultUpdate(before)
        var after=before
        preview.apply({before},{},{after=it})
        assertEquals(before.getValue("routing.enc"),after.getValue("routing.enc"))
        assertEquals(before.getValue("connections.enc"),after.getValue("connections.enc"))
        val old=JSONObject(before.getValue("preferences.enc")).getJSONObject(ConditionProfiles.KEY)
        val updated=JSONObject(after.getValue("preferences.enc")).getJSONObject(ConditionProfiles.KEY)
        assertEquals(old.getJSONArray("profiles").toString(),updated.getJSONArray("profiles").toString())
        assertEquals(old.getString("activeId"),updated.getString("activeId"))
        assertTrue(updated.getJSONObject("fallback").getJSONObject("preferences").getBoolean("automatic"))
        val stale=ConditionProfilesApply.prepareDefaultUpdate(before)
        assertThrows(IllegalArgumentException::class.java){stale.apply({before+("routing.enc" to "{}")},{},{fail("stale default update")})}
    }
}
