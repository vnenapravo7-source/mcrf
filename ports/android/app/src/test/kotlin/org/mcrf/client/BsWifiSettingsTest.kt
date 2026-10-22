package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BsWifiSettingsTest {
    @Test fun defaultNeverDisconnectsAndTrustedModeNeverAcceptsUnknownNames() {
        assertFalse(BsWifiSettings().shouldPause(true,setOf("Home")))
        val trusted=BsWifiSettings(trustedEnabled=true,trustedSsids=listOf("Home"))
        assertFalse(trusted.shouldPause(false,setOf("Home")))
        assertFalse(trusted.shouldPause(true,emptySet()))
        assertFalse(trusted.shouldPause(true,setOf("home","Other")))
        assertTrue(trusted.shouldPause(true,setOf("Home")))
        assertTrue(BsWifiSettings(pauseOnWifi=true).shouldPause(true,emptySet()))
    }
    @Test fun encryptedSettingsRoundTripStrictlyAndRejectMalformedBackupNames() {
        val value=BsWifiSettings(true,true,listOf("Home","Coffee Shop"))
        assertEquals(value,BsWifiSettings.fromJson(value.json()))
        for(bad in listOf(JSONObject().put("pauseOnWifi","true"),JSONObject().put("unknown",true),
            JSONObject().put("trustedSsids",JSONArray().put(42)),JSONObject().put("trustedSsids",JSONArray().put("A").put("A")),
            JSONObject().put("trustedSsids",JSONArray().put("<unknown ssid>")),JSONObject().put("trustedSsids",JSONArray().put("A".repeat(33)))))
            assertThrows(IllegalArgumentException::class.java){BsWifiSettings.fromJson(bad)}
    }
    @Test fun androidUnknownOrRedactedSsidIsNotTrusted() {
        assertNull(BsWifiMonitor.ssid("<unknown ssid>"));assertNull(BsWifiMonitor.ssid("\"<unknown ssid>\""))
        assertNull(BsWifiMonitor.ssid(""));assertNull(BsWifiMonitor.ssid("bad\nssid"))
        assertEquals("Home",BsWifiMonitor.ssid("\"Home\""))
        assertEquals("My Wi-Fi",BsWifiMonitor.ssid("My Wi-Fi"))
    }
}
