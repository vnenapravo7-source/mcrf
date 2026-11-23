package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteServerDraftTest {
    private val first="a".repeat(64)
    private val second="b".repeat(64)
    private fun rule(mode:String)=JSONObject().put("target","proxy").put("futureRule",JSONObject().put("keep",true))
        .put(RouteServerPlan.POOL,JSONArray(listOf(second,first)))
        .put(RouteServerPlan.AUTO,JSONObject().put("mode",mode).put("interval",137).put("tolerance",81).put("failureOnly",true).put("preferred",first))
    @Test fun untouchedPickerPreservesCompleteSequentialAndRecentPoliciesExactly() {
        for(mode in RouteEditorAuto.modes) {
            val rule=rule(mode);val before=rule.toString()
            RouteServerDraft.write(rule,false,emptyList(),false,60,50,BsAutoSelectionPolicy.FASTEST,false,"")
            assertEquals(before,rule.toString())
        }
    }
    @Test fun explicitSequenceKeepsOrderAndEveryParameterIncludingPreferredServer() {
        val rule=rule(BsAutoSelectionPolicy.RECENT)
        RouteServerDraft.write(rule,true,listOf(second,first),true,137,81,AutoSelection.SEQUENTIAL,true,first)
        assertEquals(second,rule.getJSONArray(RouteServerPlan.POOL).getString(0))
        val auto=rule.getJSONObject(RouteServerPlan.AUTO)
        assertEquals(AutoSelection.SEQUENTIAL,auto.getString("mode"));assertEquals(137,auto.getInt("interval"))
        assertEquals(81,auto.getInt("tolerance"));assertTrue(auto.getBoolean("failureOnly"));assertEquals(first,auto.getString("preferred"))
        assertTrue(rule.getJSONObject("futureRule").getBoolean("keep"))
    }
    @Test fun directOutputRemovesOnlyServerAssignmentAndRetainsNativeCondition() {
        val native=JSONObject().put("domain",JSONArray().put("native.test"))
        val rule=rule(AutoSelection.SEQUENTIAL).put("nativeMatch",native).put("target","direct")
        RouteServerDraft.write(rule,false,listOf(second,first),true,137,81,AutoSelection.SEQUENTIAL,true,first)
        assertFalse(rule.has(RouteServerPlan.KEY));assertFalse(rule.has(RouteServerPlan.POOL));assertFalse(rule.has(RouteServerPlan.AUTO))
        assertEquals(native.toString(),rule.getJSONObject("nativeMatch").toString());assertTrue(rule.has("futureRule"))
    }
}
