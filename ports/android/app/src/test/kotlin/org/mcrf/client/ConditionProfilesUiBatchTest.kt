package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ConditionProfilesUiBatchTest {
    @Test fun profileScreenReusesPersistentRouteSectionsAndUntruncatedAction() {
        fun source(name:String)=listOf(java.io.File("src/main/kotlin/org/mcrf/client/$name.kt"),
            java.io.File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
        assertTrue(source("MainActivity").contains("RoutingPage.sections(this,current,::navigate)"))
        assertTrue(source("RoutingPage").contains("control(ClientSettingsNavigation.SCHEDULE,\"Расписание\",if(narrow)16 else 20)"))
        assertTrue(source("RoutingPage").contains("control(ClientSettingsNavigation.ROUTES,\"Маршруты\",if(narrow)16 else 20)"))
        assertTrue(source("RoutingPage").contains("text(\"·\",20,Loft.MUTED)"))
        val profiles=source("ConditionProfilesUi")
        assertTrue(profiles.contains("text=\"Применять автоматически\""))
        assertTrue(profiles.contains("ChangePreviewPolicy.disable(activity)"))
        assertFalse(profiles.contains("button(\"Разрешить автоприменение…\",false)"))
        assertFalse(profiles.contains("Разрешить автоматическую смену…"))
    }
    private fun profile(id:String="home",condition:ProfileCondition=ProfileCondition.SSID,ssid:String?="Home",enabled:Boolean=true)=
        ConditionProfile(id,"Дом",condition,ssid=if(condition==ProfileCondition.SSID)ssid else null,enabled=enabled)
    @Test fun conflictHelpOnlyAppearsForOverlappingEnabledProfiles() {
        assertFalse(ConditionProfileSummary.hasConflicts(listOf(profile(),profile("work",ssid="Work"))))
        assertTrue(ConditionProfileSummary.hasConflicts(listOf(profile(),profile("all",ProfileCondition.WIFI))))
        assertTrue(ConditionProfileSummary.hasConflicts(listOf(profile(),profile("duplicate"))))
        assertFalse(ConditionProfileSummary.hasConflicts(listOf(profile(),profile("all",ProfileCondition.WIFI,enabled=false))))
        assertFalse(ConditionProfileSummary.hasConflicts(listOf(profile(),profile("mobile",ProfileCondition.CELLULAR))))
        assertTrue(ConditionProfileSummary.hasConflicts(listOf(profile("mobile",ProfileCondition.CELLULAR),profile("roam",ProfileCondition.ROAMING))))
    }
    @Test fun disabledAppliedProfileDoesNotClaimItsSettingsAreAlreadyRemoved() {
        assertEquals("Применён · выключен для автосмены",ConditionProfileSummary.state(profile(enabled=false),"home",emptySet()))
        assertEquals("Выключен",ConditionProfileSummary.state(profile(enabled=false),"other",setOf("home")))
        assertEquals("Wi-Fi · Home",ConditionProfileSummary.network(profile()))
    }
    @Test fun editingTimePreservesExistingScheduleConditionsIdentityAndPermissions() {
        val old=BehaviorSchedule.Rule("job","Работа",priority=17,enabled=false,
            conditions=BehaviorSchedule.Conditions(zoneId="Europe/Moscow",network=BehaviorSchedule.Network.MOBILE,roaming=true,charging=false,batteryAtMost=45),
            action=BehaviorSchedule.Action(profileId="home",allowActiveVpnReconfiguration=true))
        val windows=listOf(BehaviorSchedule.Window(setOf(java.time.DayOfWeek.MONDAY),1380,60))
        val next=ConditionProfileSummary.scheduled(profile(),old,windows)
        assertEquals(old.copy(conditions=old.conditions.copy(windows=windows)),next)
        assertEquals(old,old.copy(conditions=old.conditions.copy(windows=emptyList())))
    }
    @Test fun newScheduleUsesExistingProfileAndDoesNotGrantVpnReconfiguration() {
        val rule=ConditionProfileSummary.scheduled(profile(),null,emptyList())
        assertEquals("home",rule.action.profileId)
        assertFalse(rule.action.allowActiveVpnReconfiguration)
        assertEquals(0,rule.priority)
    }
    @Test(expected=IllegalArgumentException::class) fun cannotRetargetAnotherProfilesJob() {
        ConditionProfileSummary.scheduled(profile(),BehaviorSchedule.Rule("job","Another",action=BehaviorSchedule.Action(profileId="other")),emptyList())
    }
    @Test fun cardSummaryExplainsRoutesPoolAndAutomationWithoutExposingServerIds() {
        val configuration=NetworkProfileConfiguration(
            """{"rules":[{"name":"route"},{"name":"another"}]}""",
            """["private-id-1","private-id-2"]""",
            """{"automatic":true,"autoDetectBs":false}""")
        val summary=ConditionProfileSummary.describe(configuration)
        assertTrue(summary.contains("Правил: 2 · серверов: 2"))
        assertTrue(summary.contains("Автовыбор: вкл · БС: вручную"))
        assertFalse(summary.contains("private-id"));assertFalse(summary.contains("another"))
    }
    @Test fun legacyProfileWithoutConfigurationHasUsefulDescription() {
        assertEquals("Настройки ещё не сохранены",ConditionProfileSummary.describe(null))
        assertTrue(ConditionProfileSummary.describe(NetworkProfileConfiguration("""{"rules":[]}""","[]","{}")).contains("все серверы"))
    }
}
