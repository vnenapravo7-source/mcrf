package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ConditionProfilesTriggersTest {
    private val zone=ZoneId.of("UTC")
    private val morning=Instant.parse("2026-11-02T09:00:00Z") // Monday
    private val window=ProfileTimeWindow(setOf(1),540,600)
    private fun profile()=ConditionProfile("work","Работа",ProfileCondition.WIFI,windows=listOf(window))
    @Test fun networkAndTimeMustBothMatchButScheduleOnlyWorksOnAnyNetwork() {
        val both=profile()
        assertEquals(both,ConditionProfiles.resolve(listOf(both),ConditionEnvironment(wifi=true),false,morning,zone))
        assertNull(ConditionProfiles.resolve(listOf(both),ConditionEnvironment(cellular=true),false,morning,zone))
        assertNull(ConditionProfiles.resolve(listOf(both),ConditionEnvironment(wifi=true),false,morning.plusSeconds(3600),zone))
        val time=both.copy(networkTrigger=false)
        assertEquals(time,ConditionProfiles.resolve(listOf(time),ConditionEnvironment(),false,morning,zone))
        assertNull(ConditionProfiles.resolve(listOf(time),ConditionEnvironment(),true,morning,zone))
    }
    @Test fun overnightAndFullDayUseTheStartingDayAndExclusiveEnd() {
        val night=ProfileTimeWindow(setOf(1),1380,60)
        assertTrue(night.matches(Instant.parse("2026-11-03T00:59:00Z").atZone(zone)))
        assertFalse(night.matches(Instant.parse("2026-11-03T01:00:00Z").atZone(zone)))
        assertFalse(night.matches(Instant.parse("2026-11-02T00:30:00Z").atZone(zone)))
        assertTrue(ProfileTimeWindow(setOf(1),540,540).matches(morning.minusSeconds(3600).atZone(zone)))
    }
    @Test fun legacyBytesAndDefaultsArePreservedWithoutEnablingOldSchedules() {
        val old=ConditionProfile("wifi","Сеть",ProfileCondition.WIFI)
        val json=ConditionProfiles.json(listOf(old))
        val legacy=json.toString()
        assertFalse(old.json().has("networkTrigger"));assertFalse(old.json().has("windows"))
        assertEquals(listOf(old),ConditionProfiles.fromJson(json))
        assertEquals(legacy,ConditionProfiles.json(ConditionProfiles.fromJson(json)).toString())
        var document=JSONObject().put("unrelated",17).put(ConditionProfiles.KEY,json)
            .put(BehaviorSchedule.KEY,BehaviorSchedule.json(BehaviorSchedule.Settings(false,listOf(
                BehaviorSchedule.Rule("old-job","Старое",action=BehaviorSchedule.Action(profileId="wifi")))))).toString()
        val before=JSONObject(document).getJSONObject(BehaviorSchedule.KEY).toString()
        ConditionProfilesStore({document},{document=it}).save(listOf(old,profile().copy(id="timed",networkTrigger=false)))
        assertEquals(before,JSONObject(document).getJSONObject(BehaviorSchedule.KEY).toString())
        assertEquals(17,JSONObject(document).getInt("unrelated"))
    }
    @Test fun triggerRoundtripRejectsMissingOrMalformedConditions() {
        val timed=profile().copy(networkTrigger=false)
        assertEquals(listOf(timed),ConditionProfiles.fromJson(ConditionProfiles.json(listOf(timed))))
        val root=ConditionProfiles.json(listOf(timed))
        root.getJSONArray("profiles").getJSONObject(0).remove("windows")
        assertThrows(IllegalArgumentException::class.java){ConditionProfiles.fromJson(root)}
        assertThrows(IllegalArgumentException::class.java){ProfileTimeWindow(setOf(0),540,600)}
        val bad=ConditionProfiles.json(listOf(timed))
        bad.getJSONArray("profiles").getJSONObject(0).put("networkTrigger","false")
        assertThrows(IllegalArgumentException::class.java){ConditionProfiles.fromJson(bad)}
    }
    @Test fun scheduleGetsDurableBoundaryWithoutLegacyGlobalSwitch() {
        val config=NetworkProfileConfiguration(RouteRules.defaults().toString(),"[]","{}")
        val timed=profile().copy(configuration=config,networkTrigger=false)
        assertEquals(morning,BehaviorSchedule.nextProfileBoundary(listOf(timed),morning.minusSeconds(1),zone,BehaviorSchedule.Environment()))
        assertEquals(morning.plusSeconds(3600),BehaviorSchedule.nextProfileBoundary(listOf(timed),morning,zone,BehaviorSchedule.Environment()))
        assertNull(BehaviorSchedule.nextProfileBoundary(listOf(timed.copy(enabled=false)),morning,zone,BehaviorSchedule.Environment()))
        assertNull(BehaviorSchedule.nextProfileBoundary(listOf(timed.copy(networkTrigger=true)),morning,zone,BehaviorSchedule.Environment()))
    }
    @Test fun triggerEditsInvalidateApprovalButLegacyApprovalTokensRemainStable() {
        val config=NetworkProfileConfiguration(RouteRules.defaults().toString(),"[]","{}")
        val original=profile().copy(configuration=config)
        val section=ConditionProfiles.json(listOf(original))
        val token=ChangePreviewPolicy.profileToken(section,original.id)
        assertNotEquals(token,ChangePreviewPolicy.profileToken(ConditionProfiles.json(listOf(original.copy(networkTrigger=false))),original.id))
        assertNotEquals(token,ChangePreviewPolicy.profileToken(ConditionProfiles.json(listOf(original.copy(windows=listOf(window.copy(endMinute=660))))),original.id))
    }
    @Test fun timedActionsOverrideGeneralNetworkAndConflictsOnlyOverlapInTime() {
        val general=profile().copy(id="general",windows=emptyList())
        val timed=profile()
        assertEquals(timed,ConditionProfiles.resolve(listOf(general,timed),ConditionEnvironment(wifi=true),false,morning,zone))
        assertEquals(general,ConditionProfiles.resolve(listOf(general,timed),ConditionEnvironment(wifi=true),false,morning.plusSeconds(3600),zone))
        assertFalse(ConditionProfileSummary.hasConflicts(listOf(timed,timed.copy(id="later",windows=listOf(window.copy(startMinute=600,endMinute=660))))))
        val sunday=timed.copy(windows=listOf(ProfileTimeWindow(setOf(7),1380,60)))
        val monday=timed.copy(id="monday",windows=listOf(ProfileTimeWindow(setOf(1),0,30)))
        assertTrue(ConditionProfileSummary.hasConflicts(listOf(sunday,monday)))
    }
}
