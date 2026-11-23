package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChangePreviewPolicyTest {
    private fun config(target:String="proxy")=NetworkProfileConfiguration(RouteRules.defaults().put("defaultTarget",target).toString(),"[]","{\"automatic\":false}")
    private fun profile()=ConditionProfile("wifi","PRIVATE_NAME",ProfileCondition.WIFI,configuration=config())
    private fun section(p:ConditionProfile=profile())=ConditionProfiles.json(listOf(p)).put("activeId","default").put("fallback",config().json())
    private fun rule(action:BehaviorSchedule.Action=BehaviorSchedule.Action(profileId="wifi"))=
        BehaviorSchedule.Rule("rule","PRIVATE_RULE",conditions=BehaviorSchedule.Conditions(network=BehaviorSchedule.Network.WIFI),action=action)
    private fun values(section:JSONObject,schedule:BehaviorSchedule.Settings):Map<String,String> = ChangeHistoryStore.STORE_NAMES.associateWith {
        if(it=="preferences.enc")JSONObject().put(ConditionProfiles.KEY,section).put(BehaviorSchedule.KEY,BehaviorSchedule.json(schedule)).toString()else ""
    }
    @Test fun absentApprovalNeverAuthorizesAnEnabledProfileOrScheduleRule() {
        val lease=ChangePreviewPolicy.Lease("",emptySet(),false)
        assertFalse(lease.permitsProfile(section(),"wifi"));assertFalse(lease.permitsRule(rule(),section()));assertFalse(lease.allowActiveNetwork)
    }
    @Test fun emptyApprovalDeniesWithoutParsingOrChangingAnUnapprovedSnapshot() {
        val lease=ChangePreviewPolicy.Lease("",emptySet(),false)
        val malformed=JSONObject().put("profiles","not-an-array")
        val before=malformed.toString()
        assertFalse(lease.permitsProfile(malformed,"wifi"))
        assertFalse(lease.permitsRule(rule(),malformed))
        assertEquals(before,malformed.toString())
    }
    @Test fun recoveredReceiptAuthorizesOnlyTheExactSnapshotsAndRules() {
        val section=section();val r=rule();val snapshot=values(section,BehaviorSchedule.Settings(true,listOf(r)))
        val receipt=ChangePreviewPolicy.tokens(snapshot).toSet()
        val recovered=ChangePreviewPolicy.Lease("persisted-revision",receipt.toSet(),false)
        assertTrue(recovered.permitsProfile(section,"wifi"));assertTrue(recovered.permitsProfile(section,"default"));assertTrue(recovered.permitsRule(r,section))
        assertTrue(receipt.all {it.matches(Regex("(profile|rule):[a-f0-9]{64}"))});assertFalse(receipt.toString().contains("PRIVATE"))
    }
    @Test fun editedSnapshotConditionsOrVpnIntentInvalidateApproval() {
        val original=section();val r=rule();val approved=ChangePreviewPolicy.tokens(values(original,BehaviorSchedule.Settings(true,listOf(r))))
        val lease=ChangePreviewPolicy.Lease("receipt",approved,false)
        val modified=section(profile().copy(configuration=config("block")))
        assertFalse(lease.permitsProfile(modified,"wifi"));assertFalse(lease.permitsRule(r,modified))
        assertFalse(lease.permitsProfile(section(profile().copy(priority=20)),"wifi"))
        assertFalse(lease.permitsRule(r.copy(conditions=r.conditions.copy(charging=true)),original))
        assertFalse(lease.permitsRule(r.copy(action=r.action.copy(allowActiveVpnReconfiguration=true)),original))
        assertFalse(lease.permitsRule(r,ConditionProfiles.json(emptyList())))
    }
    @Test fun runtimeSelectionAndObjectKeyOrderDoNotInvalidateApprovedContent() {
        val original=section();val token=ChangePreviewPolicy.profileToken(original,"wifi")
        val switched=JSONObject(original.toString()).put("activeId","wifi")
        assertEquals(token,ChangePreviewPolicy.profileToken(switched,"wifi"))
        val reordered=JSONObject().put("fallback",original.getJSONObject("fallback")).put("profiles",original.getJSONArray("profiles")).put("activeId","default")
        assertEquals(token,ChangePreviewPolicy.profileToken(reordered,"wifi"))
    }
    @Test fun approvalReviewKeepsCommitBytesSeparateFromAlternativeDisplaySnapshots() {
        val before=values(section(),BehaviorSchedule.Settings());val alternative=before+("profile.enc" to "PRIVATE_SECRET")
        val preview=ChangePreview.preparePolicy(before,before,listOf(alternative),listOf("Безопасное действие"))
        assertFalse(preview.hasChanges);assertFalse(preview.groups.toString().contains("PRIVATE"))
        preview.apply({before},{},{assertEquals(before,it)})
        assertThrows(IllegalArgumentException::class.java) {
            ChangePreview.preparePolicy(before,before,listOf(alternative)).apply({alternative},{},{fail("stale approval persisted")})
        }
    }
}
