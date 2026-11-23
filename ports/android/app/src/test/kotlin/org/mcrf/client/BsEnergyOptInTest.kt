package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** SOURCE ONLY: run with the authorized common batch. */
class BsEnergyOptInTest {
    @Test fun explicitOptInActivatesAutoFromEveryModeWithoutResettingSavedChoices() {
        for(mode in BsScreenOffSettings.MODES) {
            val initial=BsScreenOffSettings(mode)
            val enabled=BsWifiUi.batteryOptIn(initial,true)
            assertEquals(BsScreenOffSettings("auto",BsScreenOffSettings.DEFAULT_THRESHOLD,false),enabled)
            assertEquals(mode,initial.mode)
            for(percent in listOf(1,37,100))for(charging in listOf(false,true)) {
                val saved=BsScreenOffSettings(mode,percent,charging)
                assertEquals(saved.copy(mode="auto"),BsWifiUi.batteryOptIn(saved,true))
                assertEquals(saved.copy(mode="auto"),BsWifiUi.batteryOptIn(BsWifiUi.batteryOptIn(saved,true),true))
            }
        }
    }

    @Test fun explicitDisableRetainsModeAndClearsOnlyTheThresholdAndItsDependentChargingFlag() {
        for(mode in BsScreenOffSettings.MODES)for(charging in listOf(false,true)) {
            val saved=BsScreenOffSettings(mode,37,charging)
            assertEquals(BsScreenOffSettings(mode),BsWifiUi.batteryOptIn(saved,false))
            assertEquals(saved,BsScreenOffSettings.fromJson(saved.json()))
        }
    }
}
