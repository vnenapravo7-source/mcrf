package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class BsDetectionIntervalTest {
    @Test fun supportedCadencesAndDefault(){
        assertEquals(listOf(15,30,45,60,120,180,240,300,600,900),BsDetectionInterval.seconds)
        BsDetectionInterval.seconds.forEach {assertEquals(it*1000L,BsDetectionInterval.millis(it))}
        assertEquals(60_000L,BsDetectionInterval.millis(0))
        assertEquals(60_000L,BsDetectionInterval.millis(Int.MAX_VALUE))
        assertEquals("15 с",BsDetectionInterval.label(15));assertEquals("15 мин",BsDetectionInterval.label(900))
    }
    @Test fun backupCarriesIndependentIntervalAndRejectsUnsupportedValues(){
        ClientSettingsRegistry.register()
        assertTrue(BsDetectionInterval.KEY in AndroidBackup.preferenceKeys())
        BsDetectionInterval.seconds.forEach {AndroidBackup.validatePreferences(JSONObject().put(BsDetectionInterval.KEY,it).put("interval",60))}
        assertThrows(IllegalArgumentException::class.java){AndroidBackup.validatePreferences(JSONObject().put(BsDetectionInterval.KEY,17))}
    }
}
