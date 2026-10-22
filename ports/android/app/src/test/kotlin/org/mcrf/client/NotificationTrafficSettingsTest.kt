package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotificationTrafficSettingsTest {
    @Test fun defaultsAreThreeAndTenSecondsWithNoScreenOffPolling() {
        val settings=NotificationTrafficSettings()
        assertEquals(3000L,settings.interval(true,true)!!)
        assertEquals(10000L,settings.interval(false,true)!!)
        assertNull(settings.interval(true,false));assertNull(settings.interval(false,false))
    }
    @Test fun disabledNeverPollsAndCustomIntervalsApplyOnlyWithScreenOn() {
        val disabled=NotificationTrafficSettings(false,5,20)
        for(visible in listOf(false,true))for(interactive in listOf(false,true))assertNull(disabled.interval(visible,interactive))
        val custom=disabled.copy(enabled=true)
        assertEquals(5000L,custom.interval(true,true)!!);assertEquals(20000L,custom.interval(false,true)!!)
        assertNull(custom.interval(false,false))
    }
    @Test fun backupRejectsInvalidTypesAndIntervalsWithoutTouchingOtherSettings() {
        for(value in listOf(0,301,-1,1.5,"3")) {
            try{NotificationTrafficSettings.validate(JSONObject().put(NotificationTrafficSettings.FOREGROUND,value));fail("Invalid interval accepted")}
            catch(_:IllegalArgumentException){}
        }
        try{NotificationTrafficSettings.validate(JSONObject().put(NotificationTrafficSettings.ENABLED,"true"));fail("String boolean accepted")}
        catch(_:IllegalArgumentException){}
        val valid=JSONObject().put(NotificationTrafficSettings.ENABLED,false).put(NotificationTrafficSettings.FOREGROUND,1)
            .put(NotificationTrafficSettings.BACKGROUND,300).put("unrelated","preserve")
        val before=valid.toString();NotificationTrafficSettings.validate(valid);assertEquals(before,valid.toString())
    }
    @Test fun replacingAnActivityDoesNotLetOldPauseClearTheNewForegroundOwner() {
        val first=Any();val replacement=Any();var events=0;val listener:()->Unit={events++}
        TelemetryCadence.observe(listener)
        try {
            TelemetryCadence.resumed(first);assertTrue(TelemetryCadence.uiVisible())
            TelemetryCadence.resumed(replacement);TelemetryCadence.paused(first);assertTrue(TelemetryCadence.uiVisible())
            TelemetryCadence.paused(replacement);assertFalse(TelemetryCadence.uiVisible());assertEquals(2,events)
        }finally{TelemetryCadence.paused(first);TelemetryCadence.paused(replacement);TelemetryCadence.unobserve(listener)}
    }
}
