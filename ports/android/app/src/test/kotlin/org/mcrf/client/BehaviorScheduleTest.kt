package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

class BehaviorScheduleTest {
    private val utc=ZoneId.of("UTC")
    private val toggle=BehaviorSchedule.Action(toggles=mapOf(BehaviorSchedule.Toggle.FAILURE_ONLY to true))
    private fun rule(id:String="rule",priority:Int=0,conditions:BehaviorSchedule.Conditions=BehaviorSchedule.Conditions(),action:BehaviorSchedule.Action=toggle)=
        BehaviorSchedule.Rule(id,id,priority,conditions=conditions,action=action)
    private fun decide(rules:List<BehaviorSchedule.Rule>,at:String,environment:BehaviorSchedule.Environment=BehaviorSchedule.Environment(),zone:ZoneId=utc,profiles:Set<String> = emptySet())=
        BehaviorSchedule.evaluate(BehaviorSchedule.Settings(true,rules),Instant.parse(at),zone,environment,profiles)
    @Test fun overnightRangesUseTheStartDayAndEndIsExclusive() {
        val c=BehaviorSchedule.Conditions(listOf(BehaviorSchedule.Window(setOf(DayOfWeek.MONDAY),22*60,2*60)))
        val rules=listOf(rule(conditions=c))
        assertNull(decide(rules,"2026-10-26T21:59:59Z").rule)
        assertNotNull(decide(rules,"2026-10-26T22:00:00Z").rule)
        assertNotNull(decide(rules,"2026-10-27T01:59:59Z").rule)
        assertNull(decide(rules,"2026-10-27T02:00:00Z").rule)
        assertNull(decide(rules,"2026-10-27T23:00:00Z").rule)
        assertEquals(Instant.parse("2026-10-27T02:00:00Z"),decide(rules,"2026-10-26T23:00:00Z").nextRun)
    }
    @Test fun equalTimesMeanTheWholeSelectedCalendarDay() {
        val rules=listOf(rule(conditions=BehaviorSchedule.Conditions(listOf(BehaviorSchedule.Window(setOf(DayOfWeek.MONDAY),600,600)))))
        assertNotNull(decide(rules,"2026-10-26T00:00:00Z").rule)
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"),decide(rules,"2026-10-26T09:00:00Z").nextRun)
    }
    @Test fun allEnvironmentConditionsAreConjoinedAndUnknownDoesNotMatch() {
        val c=BehaviorSchedule.Conditions(network=BehaviorSchedule.Network.MOBILE,roaming=true,charging=false,batteryAtMost=20)
        val rules=listOf(rule(conditions=c))
        val e=BehaviorSchedule.Environment(ConditionEnvironment(cellular=true,roaming=true),false,20)
        assertNotNull(decide(rules,"2026-10-31T12:00:00Z",e).rule)
        for(other in listOf(e.copy(charging=null),e.copy(charging=true),e.copy(batteryPercent=null),e.copy(batteryPercent=21),
            e.copy(network=ConditionEnvironment(wifi=true)),e.copy(network=ConditionEnvironment(cellular=true))))
            assertNull(decide(rules,"2026-10-31T12:00:00Z",other).rule)
    }
    @Test fun precedenceIsStableAndVpnDeferralNeverFallsThrough() {
        val a=rule("a",10);val z=rule("z",10);val lower=rule("lower",1,action=toggle.copy(allowActiveVpnReconfiguration=true))
        for(rules in listOf(listOf(z,lower,a),listOf(a,lower,z))) {
            val selected=decide(rules,"2026-10-31T12:00:00Z",BehaviorSchedule.Environment(vpnActive=true))
            assertEquals("a",selected.rule?.id);assertTrue(selected.deferredForVpn)
        }
        assertFalse(decide(listOf(lower),"2026-10-31T12:00:00Z",BehaviorSchedule.Environment(vpnActive=true)).deferredForVpn)
    }
    @Test fun profilesMustAlreadyExistAndActionsCannotInjectSecretsOrVpnCommands() {
        val profile=rule("profile",10,action=BehaviorSchedule.Action(profileId="existing"));val fallback=rule("safe")
        assertEquals("safe",decide(listOf(profile,fallback),"2026-10-31T12:00:00Z").rule?.id)
        assertEquals("profile",decide(listOf(profile,fallback),"2026-10-31T12:00:00Z",profiles=setOf("existing")).rule?.id)
        for(key in listOf("password","server","startVpn","stopVpn","tun")) {
            val root=BehaviorSchedule.json(BehaviorSchedule.Settings(true,listOf(fallback)))
            root.getJSONArray("rules").getJSONObject(0).getJSONObject("action").getJSONObject("toggles").put(key,true)
            assertThrows(IllegalArgumentException::class.java){BehaviorSchedule.fromJson(root)}
        }
    }
    @Test fun springGapAndAutumnOverlapHaveDeterministicNextTransitions() {
        val berlin=ZoneId.of("Europe/Berlin")
        val c=BehaviorSchedule.Conditions(listOf(BehaviorSchedule.Window(setOf(DayOfWeek.SUNDAY),150,225)))
        val rules=listOf(rule(conditions=c))
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"),decide(rules,"2026-03-29T00:50:00Z",zone=berlin).nextRun)
        assertNotNull(decide(rules,"2026-03-29T01:00:00Z",zone=berlin).rule)
        assertEquals(Instant.parse("2026-10-25T01:00:00Z"),decide(rules,"2026-10-25T00:45:00Z",zone=berlin).nextRun)
        assertNull(decide(rules,"2026-10-25T01:00:00Z",zone=berlin).rule)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"),decide(rules,"2026-10-25T01:00:00Z",zone=berlin).nextRun)
    }
    @Test fun fixedZoneIsIndependentOfDeviceZoneAndSettingsRoundtripStrictly() {
        val r=rule(conditions=BehaviorSchedule.Conditions(listOf(BehaviorSchedule.Window(setOf(DayOfWeek.SATURDAY),600,660)),"Europe/Moscow"))
        val settings=BehaviorSchedule.Settings(true,listOf(r))
        assertEquals(settings,BehaviorSchedule.fromJson(BehaviorSchedule.json(settings)))
        for(zone in listOf(utc,ZoneId.of("America/New_York")))
            assertNotNull(decide(listOf(r),"2026-10-31T07:30:00Z",zone=zone).rule)
        val root=BehaviorSchedule.json(settings).put("enabled","true")
        assertThrows(IllegalArgumentException::class.java){BehaviorSchedule.fromJson(root)}
        assertThrows(Exception::class.java){BehaviorSchedule.Conditions(zoneId="PRIVATE_INVALID_ZONE")}
    }
    @Test fun staleScheduleEditorCannotOverwriteAndUnrelatedSettingsSurvive() {
        var source="{\"theme\":\"dark\"}";val store=BehaviorScheduleStore({source},{source=it})
        val initial=store.load();val next=BehaviorSchedule.Settings(true,listOf(rule()))
        store.save(initial,next)
        assertEquals("dark",JSONObject(source).getString("theme"))
        assertEquals(next,store.load())
        assertThrows(IllegalArgumentException::class.java){store.save(initial,BehaviorSchedule.Settings())}
    }
    @Test fun disabledRulesAndRulesEntirelyInsideTheSpringGapNeverRun() {
        val gap=rule(conditions=BehaviorSchedule.Conditions(listOf(BehaviorSchedule.Window(setOf(DayOfWeek.SUNDAY),135,165))))
        val berlin=ZoneId.of("Europe/Berlin")
        assertNull(decide(listOf(gap),"2026-03-29T00:59:59Z",zone=berlin).rule)
        assertNull(decide(listOf(gap),"2026-03-29T01:00:00Z",zone=berlin).rule)
        assertEquals(Instant.parse("2026-04-05T00:15:00Z"),decide(listOf(gap),"2026-03-29T00:59:59Z",zone=berlin).nextRun)
        assertNull(decide(listOf(rule().copy(enabled=false)),"2026-10-31T12:00:00Z").rule)
        val settings=BehaviorSchedule.Settings(false,listOf(rule()))
        val selected=BehaviorSchedule.evaluate(settings,Instant.parse("2026-10-31T12:00:00Z"),utc,BehaviorSchedule.Environment(),emptySet())
        assertNull(selected.rule);assertNull(selected.nextRun)
    }
    @Test fun editorTimeSyntaxAndActionsRejectInvalidOrAmbiguousInput() {
        assertEquals(1,BehaviorScheduleUi.parseWindows("1,2 22:00-02:00").size)
        for(source in listOf("1 24:00-02:00","0 22:00-02:00","1 22:61-02:00","1 22:00-02:00 EXTRA"))
            assertThrows(Exception::class.java){BehaviorScheduleUi.parseWindows(source)}
        assertThrows(IllegalArgumentException::class.java){BehaviorSchedule.Action()}
        assertThrows(IllegalArgumentException::class.java){BehaviorSchedule.Action("existing",mapOf(BehaviorSchedule.Toggle.AUTOMATIC to false))}
        assertThrows(IllegalArgumentException::class.java){BehaviorSchedule.Settings(true,listOf(rule(),rule()))}
        assertThrows(IllegalArgumentException::class.java){BehaviorSchedule.Conditions(batteryAtMost=101)}
    }
}
