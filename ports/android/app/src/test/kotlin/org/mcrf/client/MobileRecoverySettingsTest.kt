package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MobileRecoverySettingsTest {
    @Test fun enabledPolicySchedulesFirstRetryAfterReportedMobileDrop(){
        val state=RecoveryState()
        state.start(1)
        assertTrue(state.connected(1,0))
        assertTrue(state.fail(1,RecoveryReason.NETWORK_LOSS,84_000))
        assertTrue(ClientBehaviorSettings().recoveryEnabled(RecoveryReason.NETWORK_LOSS))
        assertNotNull(state.schedule(1,84_830,0.0))
        assertEquals(1,state.snapshot().attempt)
        assertEquals(RecoveryState.Phase.WAITING_NETWORK,state.snapshot().phase)
        assertTrue(state.attempt(1,86_000))
    }

    @Test fun changingSettingsDuringConnectionMustNotUseStartupPolicy(){
        val startup=ClientBehaviorSettings().withRecovery(false).copy(resetOnNetworkChange=false)
        val saved=startup.withRecovery(true).copy(resetOnNetworkChange=true)
        assertFalse(startup.recoveryEnabled(RecoveryReason.NETWORK_LOSS))
        assertTrue(ClientBehaviorSettings.fromJson(saved.json()).recoveryEnabled(RecoveryReason.NETWORK_LOSS))
        // Verify both real service entry points refresh before inspecting the cached policy.
        val source=listOf(File("src/main/kotlin/org/mcrf/client/McrfVpnService.kt"),
            File("app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt")).first {it.isFile}.readText()
        val callback=source.substringAfter("override fun onUnderlyingNetworkChanged(").substringBefore("private fun recordHealthyConnection(")
        assertTrue(callback.indexOf("refreshRecoveryBehavior()") in 0 until callback.indexOf("!behavior.resetOnNetworkChange"))
        val begin=source.substringAfter("private fun beginRecovery(").substringBefore("private fun retryNetworkRecovery(")
        assertTrue(begin.indexOf("refreshRecoveryBehavior()") in 0 until begin.indexOf("closeCore("))
        assertTrue(begin.contains("Повтор не запланирован"))
    }

    @Test fun manualStopStillRejectsQueuedRetry(){
        val state=RecoveryState();state.start(1)
        state.fail(1,RecoveryReason.NETWORK_LOSS,0)
        assertNotNull(state.schedule(1,0,0.0))
        state.cancel(2,RecoveryReason.MANUAL_STOP)
        assertFalse(state.attempt(1,2_000))
        assertFalse(state.snapshot().userRequested)
    }
}
