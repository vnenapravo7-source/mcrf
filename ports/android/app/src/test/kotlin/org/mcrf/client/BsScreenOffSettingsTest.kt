package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BsScreenOffSettingsTest {
    @Test fun cpuLeaseRequiresExplicitModeScreenOffAndOwnedLiveSession() {
        assertFalse(BsScreenOffSettings().holdCpu(false,true))
        val stable=BsScreenOffSettings("stable")
        assertTrue(stable.holdCpu(false,true))
        assertFalse(stable.holdCpu(true,true));assertFalse(stable.holdCpu(false,false))
        assertEquals(stable,BsScreenOffSettings.fromJson(stable.json()))
    }
    @Test fun unknownBackupModesAndTypesAreRejected() {
        for(value in listOf(JSONObject().put("mode","unknown"),JSONObject().put("mode",true),JSONObject().put("extra",true)))
            assertThrows(IllegalArgumentException::class.java){BsScreenOffSettings.fromJson(value)}
    }
}
