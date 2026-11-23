package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProfilePolicyAuthorityTest {
    private val now=Instant.parse("2026-11-02T12:00:00Z")
    private val zone=ZoneId.of("UTC")
    private val wifi=BehaviorSchedule.Environment(ConditionEnvironment(wifi=true),vpnActive=true)
    private val server="a".repeat(64)
    private val other="b".repeat(64)
    private fun config(target:String="block")=NetworkProfileConfiguration(
        RouteRules.defaults().put("defaultTarget",target).put(RoutingConnectionBinding.KEY,
            JSONObject().put("enabled",true).put("vpn",JSONArray().put(server))).toString(),
        JSONArray().put(server).toString(),JSONObject().put("selected",server).put("automatic",false)
            .put("autoDetectBs",false).put("interval",300).put("tolerance",90).put("bsDetectionSelection","off").toString())
    private fun profile()=ConditionProfile("wifi","Wi-Fi",ProfileCondition.WIFI,configuration=config())
    private fun root(profile:ConditionProfile=profile(),active:String="wifi",schedule:BehaviorSchedule.Settings=BehaviorSchedule.Settings())=
        JSONObject().put(ConditionProfiles.KEY,ConditionProfiles.json(listOf(profile)).put("activeId",active).put("fallback",config("direct").json()))
            .put(BehaviorSchedule.KEY,BehaviorSchedule.json(schedule))
    private fun values(root:JSONObject,ordinary:NetworkProfileConfiguration=config("proxy"))=mapOf(
        "routing.enc" to ordinary.routing,"connections.enc" to "{\"servers\":[],\"subscriptions\":[]}",
        "preferences.enc" to root.toString(),"profile.enc" to "")
    private fun tokens(root:JSONObject)=ChangePreviewPolicy.tokens(values(root))
    private fun resolve(root:JSONObject,tokens:Set<String> = tokens(root),at:Instant=now,environment:BehaviorSchedule.Environment=wifi)=
        ProfilePolicyAuthority.snapshot(root,tokens).resolve(environment,at,zone)
    @Test fun appliedApprovedProfileOwnsRoutesBindingsPoolAndAutomaticPoliciesDuringActiveVpn() {
        val root=root();val before=root.toString();val ordinary=JSONObject(config("proxy").routing)
        ordinary.getJSONObject(RoutingConnectionBinding.KEY).put("vpn",JSONArray().put(other))
        val policy=requireNotNull(resolve(root));val effective=policy.routing {ordinary}
        assertEquals("block",effective.getString("defaultTarget"))
        assertEquals(listOf(server),RoutingConnectionBinding.selected(effective,false))
        assertEquals(setOf(server),policy.pool(setOf(server,other),setOf(other)))
        assertEquals(false,policy.values["automatic"]);assertEquals(false,policy.values["autoDetectBs"])
        assertEquals(300,policy.values["interval"]);assertEquals(90,policy.values["tolerance"])
        assertEquals("proxy",ordinary.getString("defaultTarget"));assertEquals(before,root.toString())
    }
    @Test fun approvedButNotAppliedOrEditedWithoutRenewalDoesNotGainAuthority() {
        val document=root();val approved=tokens(document)
        assertNull(resolve(root(active="default"),approved))
        assertNull(resolve(root(profile().copy(configuration=config("direct"))),approved))
        assertNull(resolve(document,emptySet()))
    }
    @Test fun disabledProfileOrMissingNetworkImmediatelyReleasesAuthority() {
        assertNull(resolve(root(profile().copy(enabled=false))))
        assertNull(resolve(root(),environment=BehaviorSchedule.Environment(ConditionEnvironment(cellular=true))))
        assertNotNull(resolve(root()))
    }
    @Test fun expiredWindowRestoresDefaultWithoutAbsorbingOrdinaryDriftOrChangingApprovalHashes() {
        val p=profile().copy(networkTrigger=false,windows=listOf(ProfileTimeWindow(setOf(1),11*60,13*60)))
        val root=root(p);val approved=tokens(root);val expired=Instant.parse("2026-11-02T13:00:00Z")
        assertNotNull(resolve(root,approved));assertNull(resolve(root,approved,expired))
        val before=values(root,config("proxy"))
        val plan=requireNotNull(ConditionProfilesApply.networkPlan(before,wifi.network,authoritative=true,now=expired,zone=zone))
        var after=before;plan.preview.apply({before},{},{after=it})
        assertEquals("default",plan.targetId)
        assertEquals("direct",JSONObject(after.getValue("routing.enc")).getString("defaultTarget"))
        assertEquals(approved,ChangePreviewPolicy.tokens(after))
        assertEquals(p.configuration,ConditionProfiles.fromJson(plan.section).single().configuration)
    }
    @Test fun matchingActiveProfileDoesNotGenerateAnotherSnapshotWriteAfterNormalSettingsDrift() {
        val root=root();val before=values(root,config("proxy"));val frozen=before.toMap()
        assertNull(ConditionProfilesApply.networkPlan(before,wifi.network,authoritative=true,now=now,zone=zone))
        assertEquals(frozen,before)
        assertEquals("block",requireNotNull(resolve(root)).routing {JSONObject(before.getValue("routing.enc"))}.getString("defaultTarget"))
    }
    @Test fun noMatchTransitionsToDefaultAndRetainsApprovedProfileBytes() {
        val root=root();val before=values(root);val approved=tokens(root)
        val plan=requireNotNull(ConditionProfilesApply.networkPlan(before,ConditionEnvironment(),authoritative=true,now=now,zone=zone))
        var after=before;plan.preview.apply({before},{},{after=it})
        assertEquals("default",plan.targetId);assertEquals(approved,ChangePreviewPolicy.tokens(after))
        assertNull(resolve(JSONObject(after.getValue("preferences.enc")),approved,environment=BehaviorSchedule.Environment()))
    }
    @Test fun scheduleWinnerReservesPriorityAndChangedRuleNeedsNewApproval() {
        val rule=BehaviorSchedule.Rule("clock","Clock",priority=20,action=BehaviorSchedule.Action(profileId="wifi"))
        val document=root(schedule=BehaviorSchedule.Settings(true,listOf(rule)));val approved=tokens(document)
        assertNotNull(resolve(document,approved))
        val changed=root(schedule=BehaviorSchedule.Settings(true,listOf(rule.copy(priority=21))))
        assertNull(resolve(changed,approved))
        val disabled=root(schedule=BehaviorSchedule.Settings(false,listOf(rule)))
        assertNotNull(resolve(disabled,approved)) // The independently approved matching network profile still applies.
    }
    @Test fun emptyProfilePoolMeansAllExistingAndMissingIdsNeverExpandToOrdinaryPool() {
        val original=config();val policy=ProfilePolicyAuthority.Policy("receipt",original,emptyMap())
        assertEquals(emptySet<String>(),policy.pool(setOf(other),setOf(other)))
        assertEquals(setOf(server,other),policy.copy(configuration=original.copy(pool="[]")).pool(setOf(server,other),setOf(other)))
    }
    @Test fun authorityLayerPreservesParametersAbsentFromLegacyProfiles() {
        val legacy=config().copy(preferences="{\"automatic\":false}")
        val policy=requireNotNull(resolve(root(profile().copy(configuration=legacy))))
        assertFalse(policy.values.containsKey("tolerance"));assertFalse(policy.values.containsKey("autoDetectBs"))
        assertEquals(false,policy.values["automatic"])
    }
    @Test fun reapprovalOfEditedActiveProfileKeepsIdButChangesRuntimePolicyToken() {
        val document=root();val original=requireNotNull(resolve(document))
        val edited=root(profile().copy(configuration=config("direct")))
        assertNull(resolve(edited,tokens(document)))
        val renewed=requireNotNull(resolve(edited))
        assertNotEquals(original.id,renewed.id)
        assertEquals("wifi",edited.getJSONObject(ConditionProfiles.KEY).getString("activeId"))
        assertEquals("direct",renewed.routing {fail("ordinary routes used");JSONObject()}.getString("defaultTarget"))
        assertNull(resolve(edited,emptySet()))
    }
}
