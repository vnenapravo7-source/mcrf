package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecoveryDefaultsMigrationTest {
    @Test fun upgradeEnablesAllControlsForEveryPriorCombination(){
        for(mask in 0..15){
            val old=ClientBehaviorSettings(reconnectOnLaunch=mask and 1!=0,
                autoRecoveryEnabled=mask and 2!=0,resetOnNetworkChange=mask and 4!=0,
                resetOnWake=mask and 8!=0,metered=true,hideRecents=true)
            val root=JSONObject().put(ClientBehaviorSettings.KEY,old.json()).put("unrelated",JSONObject().put("keep",42))
            assertTrue(RecoveryDefaultsMigration.apply(root))
            val upgraded=ClientBehaviorSettings.fromJson(root.getJSONObject(ClientBehaviorSettings.KEY))
            assertEquals(old.withRecovery(true).copy(resetOnNetworkChange=true,resetOnWake=true),upgraded)
            assertEquals(42,root.getJSONObject("unrelated").getInt("keep"))
        }
    }
    @Test fun laterUserOptOutSurvivesSerializationAndRepeatedLoads(){
        val root=JSONObject();assertTrue(RecoveryDefaultsMigration.apply(root))
        val optedOut=ClientBehaviorSettings().withRecovery(false).copy(resetOnNetworkChange=false,resetOnWake=false)
        root.put(ClientBehaviorSettings.KEY,optedOut.json())
        val persisted=JSONObject(root.toString())
        assertFalse(RecoveryDefaultsMigration.apply(persisted))
        assertEquals(optedOut,ClientBehaviorSettings.fromJson(persisted.getJSONObject(ClientBehaviorSettings.KEY)))
    }
    @Test fun newAndLegacyPreferencesGetDefaultsWithoutDroppingData(){
        for(root in listOf(JSONObject(),JSONObject().put(ClientBehaviorSettings.KEY,JSONObject().put("reconnectOnLaunch",false)))){
            assertTrue(RecoveryDefaultsMigration.apply(root))
            val settings=ClientBehaviorSettings.fromJson(root.getJSONObject(ClientBehaviorSettings.KEY))
            assertTrue(settings.recoveryEnabled);assertTrue(settings.resetOnNetworkChange);assertTrue(settings.resetOnWake)
            assertFalse(RecoveryDefaultsMigration.apply(root))
        }
    }
}
