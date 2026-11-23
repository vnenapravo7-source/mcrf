package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdaptivePolicyTest {
    @Test fun resourceBudgetsOnlySlowOptionalWork() {
        val settings=ClientBehaviorSettings(adaptivePolicyEnabled=true,conserveMobile=true)
        val budget=AdaptivePolicy.budget(settings,AdaptivePolicy.Environment(mobile=true),60_000,3_600_000)
        assertEquals(300_000L,budget.probeIntervalMs);assertEquals(86_400_000L,budget.updateIntervalMs)
        assertEquals(900_000L,AdaptivePolicy.budget(settings,AdaptivePolicy.Environment(lowBattery=true),900_000,0).probeIntervalMs)
        assertEquals(60_000L,AdaptivePolicy.budget(settings.copy(adaptivePolicyEnabled=false),AdaptivePolicy.Environment(roaming=true),60_000,0).probeIntervalMs)
    }
    @Test fun unstableRequiresConfiguredFailuresAndRetiredRealtimeCannotSuppressHealthySelection() {
        val settings=ClientBehaviorSettings(adaptivePolicyEnabled=true,unstableNetwork=true)
        val guard=AdaptivePolicy.SwitchGuard()
        repeat(2){guard.observe("a",false);assertFalse(guard.mayReconsider(settings,false))}
        guard.observe("a",false);assertTrue(guard.mayReconsider(settings,false))
        guard.observe("a",true);guard.observe("a",false);assertFalse(guard.mayReconsider(settings,false))
        guard.observe("b",false);assertFalse(guard.mayReconsider(settings,false))
        assertTrue(guard.mayReconsider(settings.copy(prioritizeRealtime=true),true))
    }
    @Test fun switchGuardRejectsPinsCooldownRollbackAndLoops() {
        val guard=AdaptivePolicy.SwitchGuard()
        assertFalse(guard.admit("a","b",1,true));assertTrue(guard.admit("a","b",1,false))
        assertFalse(guard.admit("b","c",2,false));assertFalse(guard.admit("b","a",120_001,false))
        assertTrue(guard.admit("b","c",120_001,false));assertFalse(guard.admit("c","d",0,false))
        assertTrue(guard.admit("c","a",2_000_000,false))
    }
    @Test fun settingsRoundTripAndLegacyDefaultsRemainExplicit() {
        val old=ClientBehaviorSettings.fromJson(JSONObject())
        assertFalse(old.adaptivePolicyEnabled);assertFalse(old.prioritizeRealtime)
        val changed=old.copy(adaptivePolicyEnabled=true,prioritizeRealtime=true,unstableNetwork=true,foregroundPolicyEnabled=true)
        assertEquals(changed,ClientBehaviorSettings.fromJson(changed.json()))
    }
}
