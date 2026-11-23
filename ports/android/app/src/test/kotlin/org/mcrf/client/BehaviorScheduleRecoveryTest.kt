package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BehaviorScheduleRecoveryTest {
    @Test fun recoverySurvivesProcessStateLossAndOnlyNeedsPersistedConfiguration() {
        val rule=BehaviorSchedule.Rule("rule","Rule",action=BehaviorSchedule.Action(toggles=mapOf(BehaviorSchedule.Toggle.AUTOMATIC to false)))
        val root=JSONObject().put(BehaviorSchedule.KEY,BehaviorSchedule.json(BehaviorSchedule.Settings(true,listOf(rule))))
        assertTrue(BehaviorScheduleRecovery.configured(JSONObject(root.toString())))
        assertFalse(BehaviorScheduleRecovery.configured(JSONObject().put(BehaviorSchedule.KEY,BehaviorSchedule.json(BehaviorSchedule.Settings(false,listOf(rule))))))
        assertFalse(BehaviorScheduleRecovery.configured(JSONObject()))
    }
    @Test fun periodicAndBoundaryJobIdsDoNotCollideAndLatencyIsClockSafe() {
        val ids=listOf(BackgroundJobIds.SUBSCRIPTIONS,BackgroundJobIds.ENGINES,BackgroundJobIds.SUBSCRIPTIONS_ON_OPEN,
            BackgroundJobIds.ROUTING,BackgroundJobIds.BEHAVIOR_RECOVERY,BackgroundJobIds.BEHAVIOR_NEXT)
        assertEquals(ids.size,ids.distinct().size)
        assertEquals(0L,BehaviorScheduleRecovery.latency(100,99));assertEquals(0L,BehaviorScheduleRecovery.latency(100,100))
        assertEquals(60_000L,BehaviorScheduleRecovery.latency(100,60_100))
        assertEquals(8*24*3_600_000L,BehaviorScheduleRecovery.latency(Long.MIN_VALUE,Long.MAX_VALUE))
    }
    @Test fun stopCancelsOnlyItsOwnJobAndStaleCompletionCannotFinishTheReplacement() {
        val runs=BehaviorScheduleJobRuns<String>();val periodic=runs.start(1,"periodic");val first=runs.start(2,"first-boundary")
        assertTrue(runs.stop(2));assertTrue(first.cancelled.get());assertFalse(periodic.cancelled.get())
        val replacement=runs.start(2,"replacement")
        assertFalse(runs.finish(2,first));assertFalse(replacement.cancelled.get())
        assertTrue(runs.finish(1,periodic));assertTrue(runs.finish(2,replacement));assertFalse(runs.finish(2,replacement))
    }
    @Test fun serviceDestructionCancelsAllEvidenceTokensWithoutCompletingJobs() {
        val runs=BehaviorScheduleJobRuns<String>();val first=runs.start(1,"first");val second=runs.start(2,"second")
        assertEquals(setOf(1,2),runs.destroy());assertTrue(first.cancelled.get());assertTrue(second.cancelled.get())
        assertFalse(runs.finish(1,first));assertFalse(runs.finish(2,second))
    }
}
