package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SpeedTestStateTest {
    private val request = SpeedTestRequest("cloudflare", SpeedTestPath.APP_NETWORK)
    @Test fun exactProviderSetRejectsUnavailableAdaptersRatherThanRelabellingCloudflare() {
        assertEquals(listOf("cloudflare"), SpeedTestProviders.all.map { it.id })
        assertEquals("speed.cloudflare.com", SpeedTestProviders.adapter("cloudflare").host)
        for (id in listOf("ookla", "yandex")) {
            assertNull(SpeedTestProviders.find(id))
            assertThrows(IllegalArgumentException::class.java) { SpeedTestProviders.adapter(id) }
            val state = SpeedTestState()
            assertThrows(IllegalStateException::class.java) { state.begin(request.copy(providerId = id)) }
            assertEquals(SpeedTestPhase.IDLE, state.snapshot.phase)
        }
        assertThrows(IllegalArgumentException::class.java) { SpeedTestProviders.adapter("librespeed") }
    }
    @Test fun stopRetainsAdmissionUntilRetirementAndDiscardsLateSuccess() {
        val state = SpeedTestState()
        val first = state.begin(request)!!
        state.progress(first.runId, "owned route", SpeedTestProgress(SpeedTestStage.DOWNLOAD, 1, 100))
        assertTrue(state.stop(first.runId, "cancel"))
        assertFalse(state.stop(first.runId, "timeout"))
        assertNull(state.begin(request))
        state.progress(first.runId, "late progress")
        assertEquals(SpeedTestPhase.STOPPING, state.snapshot.phase)
        state.finish(first.runId, SpeedTestResult(1.0, 2.0, 3.0), "")
        assertNull(state.snapshot.result); assertEquals("cancel", state.snapshot.message)
        assertNotNull(state.begin(request))
    }
    @Test fun staleActionsAndCompletionCannotStopOrReplaceNewRun() {
        val state = SpeedTestState()
        val first = state.begin(request)!!
        state.finish(first.runId, null, "network")
        val next = state.begin(request)!!
        assertNotEquals(first.runId, next.runId)
        assertFalse(state.stop(first.runId, "old action"))
        state.finish(first.runId, SpeedTestResult(1.0, 2.0, 3.0), "")
        assertEquals(next, state.snapshot)
    }
    @Test fun duplicateStartAndIncorrectSavedServerShapeCannotChangeOwner() {
        val state = SpeedTestState()
        assertThrows(IllegalStateException::class.java) { state.begin(request.copy(path = SpeedTestPath.SAVED_SERVER)) }
        val owner = state.begin(request)!!
        assertNull(state.begin(request)); assertEquals(owner, state.snapshot)
        state.finish(owner.runId, null, "failed")
        assertNotNull(state.begin(SpeedTestRequest("cloudflare", SpeedTestPath.SAVED_SERVER, "fixture-id")))
    }
}
