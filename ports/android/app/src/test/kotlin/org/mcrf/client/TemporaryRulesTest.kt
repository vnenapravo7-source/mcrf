package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TemporaryRulesTest {
    private val now=TemporaryRules.Clock(1_000_000,100_000,4,"generation-one")
    private fun rule(id:String="temporary")=JSONObject().put("id",id).put("name",id)
        .put("text","example.test").put("mode","addresses").put("target","direct").put("enabled",true)
    private fun state()=RouteRules.defaults().apply {RoutingGroups.ensure(this);ExperienceRouting.ensure(this)}
    private fun view(root:JSONObject,mode:ExperienceMode=ExperienceMode.EXPERT)=ExperienceRouting.project(root,mode).apply {
        optJSONObject(TemporaryRules.KEY)?.put("viewMode",mode.id)
    }
    private fun ids(root:JSONObject)=root.getJSONArray("rules").let {a->(0 until a.length()).map {a.getJSONObject(it).getString("id")}}
    @Test fun canCreateFromAnEmptyPermanentList() {
        val root=state();assertTrue(ids(root).isEmpty())
        TemporaryRules.start(root,"expert","vpn",rule(),5,now)
        RoutingStore.validate(root)
        assertTrue(ids(root).isEmpty())
        assertEquals(listOf("temporary"),ids(TemporaryRules.project(view(root),now)))
    }
    @Test fun stoppingOrExpiringNeverWritesAnOldPermanentRule() {
        val root=state();root.getJSONArray("rules").put(rule("permanent"));val id=TemporaryRules.start(root,"expert","vpn",rule(),5,now)
        root.getJSONArray("rules").getJSONObject(0).put("name","Edited while temporary rule is active").put("target","block")
        val effective=TemporaryRules.project(view(root),now)
        assertEquals(listOf("temporary","permanent"),ids(effective))
        TemporaryRules.stop(root,id)
        assertEquals("block",root.getJSONArray("rules").getJSONObject(0).getString("target"))
        assertEquals("Edited while temporary rule is active",root.getJSONArray("rules").getJSONObject(0).getString("name"))
        TemporaryRules.start(root,"expert","vpn",rule(),5,now)
        assertTrue(TemporaryRules.reconcile(root,now.copy(wall=now.wall+300_000)))
        assertEquals(listOf("permanent"),ids(root));assertFalse(root.has(TemporaryRules.KEY))
    }
    @Test fun deadlineSurvivesProcessDeathRebootAndClockRollback() {
        val root=state();TemporaryRules.start(root,"expert","vpn",rule(),5,now)
        val recovered=JSONObject(root.toString());val row=TemporaryRules.rows(recovered).single()
        assertFalse(TemporaryRules.expired(row,now.copy(wall=now.wall+299_999,elapsed=now.elapsed+299_999)))
        assertTrue(TemporaryRules.expired(row,now.copy(elapsed=now.elapsed+300_000)))
        assertTrue(TemporaryRules.expired(row,now.copy(wall=now.wall-1)))
        assertTrue(TemporaryRules.expired(row,now.copy(wall=now.wall+300_000,boot=5,elapsed=10)))
        assertTrue(ids(TemporaryRules.project(view(recovered),now.copy(wall=now.wall+300_000,boot=5,elapsed=10))).isEmpty())
    }
    @Test fun restoringHistoryDoesNotReviveAnOldDeadline() {
        val root=state();TemporaryRules.start(root,"expert","vpn",rule(),15,now)
        val restored=now.copy(generation="after-restore")
        assertTrue(ids(TemporaryRules.project(view(root),restored)).isEmpty())
        assertTrue(TemporaryRules.reconcile(root,restored));assertFalse(root.has(TemporaryRules.KEY))
    }
    @Test fun modeAndGroupAreIsolatedAndPermanentAppPoliciesAreUnchanged() {
        val root=state();root.put("excludedApps",JSONArray().put("org.example.direct"))
        TemporaryRules.start(root,"expert","vpn",rule(),5,now)
        assertEquals(0,TemporaryRules.active(root,"expert","bs",now).size)
        assertTrue(ids(TemporaryRules.project(view(root,ExperienceMode.SIMPLE),now)).isEmpty())
        val other=view(root);RoutingGroups.select(other,"bs")
        assertTrue(ids(TemporaryRules.project(other,now)).isEmpty())
        assertEquals("org.example.direct",root.getJSONArray("excludedApps").getString(0))
    }
    @Test fun duplicateOrPermanentIdsCannotBeOverlaid() {
        val root=state();root.getJSONArray("rules").put(rule("permanent"))
        assertThrows(IllegalArgumentException::class.java){TemporaryRules.start(root,"expert","vpn",rule("permanent"),5,now)}
        TemporaryRules.start(root,"expert","vpn",rule(),5,now)
        assertThrows(IllegalArgumentException::class.java){TemporaryRules.start(root,"expert","bs",rule(),5,now)}
        assertThrows(IllegalArgumentException::class.java){TemporaryRules.start(root,"simple","vpn",rule("other"),5,now)}
    }
    @Test fun multipleTemporaryRulesCanBeCreatedAndStoppedIndependently() {
        val root=state();val first=TemporaryRules.start(root,"expert","vpn",rule("first"),5,now)
        TemporaryRules.start(root,"expert","vpn",rule("second"),15,now)
        TemporaryRules.stop(root,first)
        assertEquals(listOf("second"),ids(TemporaryRules.project(view(root),now)))
        RoutingStore.validate(root)
    }
    @Test fun projectionDoesNotModifyPersistedState() {
        val root=state();TemporaryRules.start(root,"expert","vpn",rule(),5,now)
        val before=root.toString();TemporaryRules.project(view(root),now)
        assertEquals(before,root.toString())
    }
    @Test fun retiringLegacyMetadataDoesNotReplaySnapshotsOrChangeAnyRuleBank() {
        val root=state();root.getJSONArray("rules").put(rule("actual"))
        val old=JSONObject().put("sessions",JSONArray().put(JSONObject().put("original",JSONObject().put("rules",JSONArray().put(rule("obsolete"))))))
        root.put("ruleSandbox",old)
        val expert=root.getJSONObject(ExperienceRouting.KEY).getJSONObject("simple")
        expert.put("ruleSandbox",old)
        assertTrue(TemporaryRules.removeLegacy(root));assertFalse(TemporaryRules.removeLegacy(root))
        assertEquals(listOf("actual"),ids(root));assertFalse(expert.has("ruleSandbox"))
        assertTrue(ids(view(root,ExperienceMode.SIMPLE)).isEmpty())
        RoutingStore.validate(root)
    }
    @Test fun durationAndMalformedMetadataAreRejected() {
        val root=state()
        assertThrows(IllegalArgumentException::class.java){TemporaryRules.start(root,"expert","vpn",rule(),0,now)}
        assertThrows(IllegalArgumentException::class.java){TemporaryRules.start(root,"expert","vpn",rule(),1441,now)}
        TemporaryRules.start(root,"expert","vpn",rule(),5,now)
        TemporaryRules.rows(root).single().put("original",JSONObject())
        assertThrows(IllegalArgumentException::class.java){TemporaryRules.validate(root)}
    }
}
