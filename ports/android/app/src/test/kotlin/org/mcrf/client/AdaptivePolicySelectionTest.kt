package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdaptivePolicySelectionTest {
    private fun server(port:Int)=ServerImport.read("socks://127.0.0.1:$port#test").single()
    @Test fun subscriptionSwitchRequiresUniqueDifferentOwnershipAndFreshSuccess() {
        val a=server(1080);val b=server(1081);val c=server(1082);val now=1_000_000L
        val probes=JSONObject().put(a.id,JSONObject().put("at",now).put("delay",10))
            .put(b.id,JSONObject().put("at",now).put("delay",30)).put(c.id,JSONObject().put("at",now-300_001).put("delay",1))
        val memberships=mapOf(a.id to "sub-a",b.id to "sub-b",c.id to "sub-c")
        assertEquals(listOf(b.id),AutoSelection.policyCandidates(listOf(a,b,c),probes,now,a.id,"sub-a",memberships).map {it.id})
        assertTrue(AutoSelection.policyCandidates(listOf(a,b),probes,now,a.id,UsageQuotaPolicy.UNKNOWN,memberships).isEmpty())
        assertTrue(AutoSelection.policyCandidates(listOf(a,b),probes,now,a.id,"sub-a",memberships+(b.id to "sub-a")).isEmpty())
    }
    @Test fun sharedSubscriptionEntriesRemainUnknown() {
        val entry=server(1080)
        fun sub(id:String)=JSONObject().put("id",id).put("servers",org.json.JSONArray(listOf(entry.original)))
        assertEquals(UsageQuotaPolicy.UNKNOWN,AutoSelection.subscriptionMembership(listOf(sub("a"),sub("b")))[entry.id])
    }
    @Test fun warnOnlyLimitDoesNotExcludeSelection() {
        val rule=UsageQuotaPolicy.Rule("server",1,UsageQuotaPolicy.Period.DAILY,scope=UsageQuotaPolicy.Scope.SERVER,subject="a".repeat(64))
        val hit=UsageQuotaStore.Hit(rule,UsageQuotaPolicy.Window(0,1),1)
        assertTrue(AutoSelection.allowedByQuota(rule.subject,"sub",listOf(hit)))
        assertFalse(AutoSelection.allowedByQuota(rule.subject,"sub",listOf(hit.copy(rule=rule.copy(action=UsageQuotaPolicy.Action.SWITCH_SERVER)))))
    }
}
