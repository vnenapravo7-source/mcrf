package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring checks only: these do not simulate Android storage or scheduler delivery. */
class BehaviorScheduleFastPathSourceContractTest {
    private fun source(name:String)=listOf(
        File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")
    ).first {it.isFile}.readText()

    @Test fun emptyReceiptExitFollowsArbitrationAndCannotFallThroughToNetworkPreview() {
        val body=source("BehaviorScheduleRuntime").substringAfter("fun evaluateNow(").substringBefore("private fun applyToggles")
        val decision=body.indexOf("val selected=decision(before,env);nextRun=selected.nextRun")
        val rule=body.indexOf("if(rule!=null)")
        val ruleDenial=body.indexOf("if(!lease.permitsRule(rule,section))")
        val empty=body.indexOf("if(lease.tokens.isEmpty())")
        val preview=body.indexOf("val plan=ConditionProfilesApply.networkPlan(")
        assertTrue(decision>=0&&rule>decision&&ruleDenial>rule&&empty>ruleDenial&&preview>empty)
        assertTrue(body.substring(empty,preview).contains(";return}"))
        assertTrue(body.contains("finally {"))
        assertTrue(body.contains("BehaviorScheduleRecovery.schedule(context,nextRun)"))
    }

    @Test fun nonemptyReceiptsStillUseExactTokensAndFreshCommitGuards() {
        val policy=source("ChangePreviewPolicy")
        assertTrue(policy.contains("tokens.isNotEmpty()&&profileToken(section,id)?.let {it in tokens}==true"))
        assertTrue(policy.contains("tokens.isNotEmpty()&&ruleToken(rule,section)?.let {it in tokens}==true"))
        val runtime=source("BehaviorScheduleRuntime")
        assertTrue(runtime.contains("!lease.current(context)"))
        assertTrue(runtime.contains("current(rule.id)&&lease.permitsRule(rule,section)"))
        assertTrue(runtime.contains("current(null)&&lease.permitsProfile(section,plan.targetId)&&lease.permitsProfile(plan.section,plan.targetId)"))
    }
}
