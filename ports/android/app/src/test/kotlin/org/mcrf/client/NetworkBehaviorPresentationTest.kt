package org.mcrf.client

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class NetworkBehaviorPresentationTest {
    @Test fun displayedProfileOrderAgreesWithRuntimeForOverlappingConditions() {
        val profiles=listOf(
            ConditionProfile("wifi","Любой Wi-Fi",ProfileCondition.WIFI),
            ConditionProfile("home","Дом",ProfileCondition.SSID,ssid="Home"),
            ConditionProfile("priority","Сохранённый",ProfileCondition.WIFI,priority=10),
            ConditionProfile("disabled","Выключен",ProfileCondition.SSID,priority=100,ssid="Home",enabled=false))
        val environment=ConditionEnvironment(wifi=true,ssids=setOf("Home"),locationAllowed=true)
        val displayed=NetworkBehaviorPresentation.profiles(profiles)
        assertEquals(ConditionProfiles.resolve(profiles,environment,false),displayed.first {it.enabled})
        assertEquals("home",NetworkBehaviorPresentation.profiles(profiles.filter {it.priority==0}).first().id)
        assertEquals(listOf(0,0,10,100),profiles.map {it.priority}) // No persisted priority is rewritten.
    }
    @Test fun visibleScheduleOrderMatchesActualWinnerIncludingLegacyPriorityAndTie() {
        val action=BehaviorSchedule.Action(toggles=mapOf(BehaviorSchedule.Toggle.AUTOMATIC to true))
        val rules=listOf(BehaviorSchedule.Rule("z","Z",8,action=action),
            BehaviorSchedule.Rule("a","A",8,action=action),BehaviorSchedule.Rule("lower","Lower",1,action=action))
        val winner=BehaviorSchedule.evaluate(BehaviorSchedule.Settings(true,rules),Instant.parse("2026-11-02T12:00:00Z"),
            ZoneId.of("UTC"),BehaviorSchedule.Environment(),emptySet()).rule
        assertEquals(winner,NetworkBehaviorPresentation.rules(rules).first())
        assertEquals(listOf("a","z","lower"),NetworkBehaviorPresentation.rules(rules).map {it.id})
    }
    @Test fun dayAndTimeSummaryHandlesFullDaysOvernightAndMultipleIntervals() {
        val weekdays=DayOfWeek.values().take(5).toSet()
        assertEquals("По будням",NetworkBehaviorPresentation.days(weekdays))
        assertEquals("Каждый день",NetworkBehaviorPresentation.days(DayOfWeek.values().toSet()))
        assertEquals("Пн, Ср",NetworkBehaviorPresentation.days(setOf(DayOfWeek.WEDNESDAY,DayOfWeek.MONDAY)))
        assertEquals("Пн · 22:00–02:00 (+1 день)",NetworkBehaviorPresentation.window(BehaviorSchedule.Window(setOf(DayOfWeek.MONDAY),1320,120)))
        assertEquals("По будням · весь день",NetworkBehaviorPresentation.window(BehaviorSchedule.Window(weekdays,600,600)))
        assertEquals("00:00",NetworkBehaviorPresentation.time(0));assertEquals("23:59",NetworkBehaviorPresentation.time(1439))
        assertThrows(IllegalArgumentException::class.java){NetworkBehaviorPresentation.time(1440)}
        val conditions=BehaviorSchedule.Conditions(listOf(BehaviorSchedule.Window(weekdays,540,1080)),"Europe/Moscow",charging=true,batteryAtMost=30)
        val summary=NetworkBehaviorPresentation.schedule(conditions)
        for(fragment in listOf("09:00–18:00","Europe/Moscow","На зарядке","30%"))assertTrue(summary.contains(fragment))
        assertTrue(NetworkBehaviorPresentation.schedule(BehaviorSchedule.Conditions()).contains("В любое время"))
        assertEquals(conditions,BehaviorSchedule.fromJson(BehaviorSchedule.json(BehaviorSchedule.Settings(rules=listOf(
            BehaviorSchedule.Rule("roundtrip","R",conditions=conditions,action=BehaviorSchedule.Action(toggles=mapOf(BehaviorSchedule.Toggle.DETECT_BS to true))))))).rules.single().conditions)
    }
}
