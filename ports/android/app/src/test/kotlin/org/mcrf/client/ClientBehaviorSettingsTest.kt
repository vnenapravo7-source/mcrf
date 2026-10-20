package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClientBehaviorSettingsTest {
    @Test fun defaultsDoNotStartAnUnrequestedConnection(){
        val value=ClientBehaviorSettings.fromJson(JSONObject())
        assertTrue(value.reconnectOnLaunch);assertTrue(value.resetOnWake);assertTrue(value.resetOnNetworkChange)
        assertFalse(value.shouldReconnect(false,true,true,false))
        assertFalse(value.shouldReconnect(true,false,true,false))
        assertFalse(value.shouldReconnect(true,true,false,false))
    }
    @Test fun onlyPreviouslyWantedConsentedIdleConnectionResumes(){
        val value=ClientBehaviorSettings(reconnectOnLaunch=true)
        assertTrue(value.shouldReconnect(true,true,true,false))
        assertFalse(value.shouldReconnect(false,true,true,false));assertFalse(value.shouldReconnect(true,false,true,false))
        assertFalse(value.shouldReconnect(true,true,false,false));assertFalse(value.shouldReconnect(true,true,true,true))
        assertEquals(value,ClientBehaviorSettings.fromJson(value.json()))
    }
    @Test fun explicitManualOptOutSurvivesReload(){
        val value=ClientBehaviorSettings.fromJson(JSONObject().put("reconnectOnLaunch",false))
        assertFalse(value.reconnectOnLaunch);assertFalse(value.shouldReconnect(true,true,true,false))
        assertFalse(ClientBehaviorSettings.fromJson(value.json()).reconnectOnLaunch)
    }
    @Test fun backupRejectsUnknownKeysAndCoercedBoolean(){
        for(json in listOf(JSONObject().put("hideRecents","true"),JSONObject().put("unknown",true),JSONObject().put("metered",1))){
            try{ClientBehaviorSettings.fromJson(json);fail("Must reject invalid settings")}catch(_:IllegalArgumentException){}
        }
    }
}
