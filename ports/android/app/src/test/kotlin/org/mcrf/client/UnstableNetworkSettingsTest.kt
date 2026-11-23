package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UnstableNetworkSettingsTest {
    @Test fun killSwitchInAlreadyOpenPagePreservesPopupChanges() {
        val opened=ClientBehaviorSettings()
        val latest=opened.copy(unstableNetwork=true,unstableFailures=7,unstableIntervalSeconds=300)
        val merged=ClientBehaviorSettings.fromJson(RoutingDefaultsEdits.merge(latest.json(),opened.json(),opened.copy(killSwitchBeta=true).json()))
        assertTrue(merged.killSwitchBeta);assertTrue(merged.unstableNetwork)
        assertEquals(7,merged.unstableFailures);assertEquals(300,merged.unstableIntervalSeconds)
    }
    @Test fun chosenThresholdAndIntervalSurviveSerialization() {
        val settings=ClientBehaviorSettings(unstableNetwork=true,unstableFailures=5,unstableIntervalSeconds=180)
        val restored=ClientBehaviorSettings.fromJson(JSONObject(settings.json().toString()))
        assertEquals(settings,restored)
        val guard=AdaptivePolicy.SwitchGuard()
        repeat(4){guard.observe("a",false);assertFalse(guard.mayReconsider(restored,false))}
        guard.observe("a",false);assertTrue(guard.mayReconsider(restored,false))
        guard.observe("a",true);guard.observe("a",false);assertFalse(guard.mayReconsider(restored,false))
        guard.observe("b",false);assertFalse(guard.mayReconsider(restored,false))
    }
    @Test fun oldSnapshotsKeepExplicitDefaultsAndRejectInvalidNumbers() {
        val old=ClientBehaviorSettings.fromJson(JSONObject().put("unstableNetwork",true))
        assertEquals(3,old.unstableFailures);assertEquals(60,old.unstableIntervalSeconds)
        for(json in listOf(JSONObject().put("unstableFailures",0),JSONObject().put("unstableFailures",11),
            JSONObject().put("unstableIntervalSeconds",4),JSONObject().put("unstableIntervalSeconds","60"))) {
            assertTrue(runCatching {ClientBehaviorSettings.fromJson(json)}.isFailure)
        }
    }
}
