package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BsScreenOffSettingsTest {
    // SOURCEONLY: prepare for the next authorized common gate; do not run separately.
    @Test fun cpuLeaseRequiresExplicitModeScreenOffAndOwnedLiveSession() {
        assertFalse(BsScreenOffSettings().holdCpu(false,true))
        val stable=BsScreenOffSettings("stable")
        assertTrue(stable.holdCpu(false,true))
        assertFalse(stable.holdCpu(true,true));assertFalse(stable.holdCpu(false,false))
        assertEquals(stable,BsScreenOffSettings.fromJson(stable.json()))
    }
    @Test fun unknownBackupModesAndTypesAreRejected() {
        for(value in listOf(JSONObject().put("mode","unknown"),JSONObject().put("mode",true),JSONObject().put("mode",1),
            JSONObject().put("mode",JSONObject.NULL),JSONObject().put("mode","AUTO"),JSONObject().put("extra",true)))
            assertThrows(IllegalArgumentException::class.java){BsScreenOffSettings.fromJson(value)}
    }

    @Test fun modePowerSessionAndScreenMatrix() {
        for(mode in listOf("balanced","stable","auto"))for(saver in listOf(false,true))
            for(alive in listOf(false,true))for(screenOn in listOf(false,true)) {
                val expected=when(mode){
                    "balanced"->false
                    "stable"->alive&&!screenOn
                    else->alive&&!screenOn&&!saver
                }
                assertEquals("mode=$mode saver=$saver alive=$alive screenOn=$screenOn",expected,
                    BsScreenOffSettings(mode).holdCpu(screenOn,alive,saver))
            }
    }

    @Test fun autoReleasesOnSaverAndResumesOnlyForLiveScreenOffSession() {
        val auto=BsScreenOffSettings("auto")
        assertTrue(auto.holdCpu(false,true,false))
        assertFalse(auto.holdCpu(false,true,true))
        assertTrue(auto.needsLease) // Keep the observer so saver-off can resume the same session.
        assertTrue(auto.holdCpu(false,true,false))
        assertFalse(auto.holdCpu(true,true,false))
        assertFalse(auto.holdCpu(false,false,false)) // Stop, Wi-Fi pause, or replaced session.
        assertFalse(auto.holdCpu(false,false,true))
        assertTrue(auto.holdCpu(false,true,false)) // A fresh eligible session can take its own lease.
    }

    @Test fun serviceEligibilityRetainsAutoObserverAndExplicitModes() {
        assertFalse(BsScreenOffSettings("balanced").needsLease)
        assertTrue(BsScreenOffSettings("stable").needsLease)
        assertTrue(BsScreenOffSettings("auto").needsLease)
        assertTrue(BsScreenOffSettings("stable").holdCpu(false,true,true))
        assertFalse(BsScreenOffSettings("balanced").holdCpu(false,true,false))
    }

    @Test fun mcrfDefaultStaysBalancedAndAllExplicitChoicesRoundTripWithoutMutation() {
        assertEquals("balanced",BsScreenOffSettings().mode)
        assertEquals("balanced",BsScreenOffSettings.fromJson(JSONObject()).mode)
        val absent=JSONObject().put("unrelated","keep")
        val before=absent.toString()
        assertEquals("balanced",BsScreenOffSettings.fromPreferences(absent).mode)
        assertEquals(before,absent.toString())
        assertFalse(absent.has(BsScreenOffSettings.KEY))
        for(mode in listOf("balanced","stable","auto")) {
            val root=JSONObject().put("unrelated","keep").put(BsScreenOffSettings.KEY,JSONObject().put("mode",mode))
            val original=root.toString()
            val loaded=BsScreenOffSettings.fromPreferences(root)
            assertEquals(mode,loaded.mode)
            assertEquals(loaded,BsScreenOffSettings.fromJson(loaded.json()))
            assertEquals(original,root.toString())
        }
    }

    @Test fun malformedStoredSectionCannotBecomeDefaultOrAnAutomaticChoice() {
        for(value in listOf<Any>(JSONObject.NULL,true,1,"stable"))
            assertThrows(IllegalArgumentException::class.java){
                BsScreenOffSettings.fromPreferences(JSONObject().put(BsScreenOffSettings.KEY,value))
            }
        assertThrows(IllegalArgumentException::class.java){BsScreenOffSettings("unknown")}
    }
}
