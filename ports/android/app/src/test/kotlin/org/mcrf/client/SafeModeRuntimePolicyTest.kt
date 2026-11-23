package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SafeModeRuntimePolicyTest {
    @Test fun enablingCancelsQueuedRetryBeforeReleasingOwnedBlockingTun() {
        val recovery=RecoveryState();recovery.start(7)
        recovery.fail(7,RecoveryReason.NETWORK_LOSS,100)
        assertNotNull(recovery.schedule(7,100,0.5))
        recovery.blocked(7,true)
        val effects=mutableListOf<String>()
        assertTrue(SafeModeRuntimePolicy.apply(true,recovery.snapshot(),true,true,{
            effects+="cancel";recovery.cancel(8,RecoveryReason.CANCELLED)
        },{effects+="close-owned-fd"}))
        assertEquals(listOf("cancel","close-owned-fd"),effects)
        assertFalse(recovery.attempt(7,3000));assertFalse(recovery.connected(7,3000))
        assertFalse(recovery.snapshot().trafficBlocked);assertFalse(recovery.snapshot().userRequested)
    }
    @Test fun healthyVpnAndNoMcrfSessionHaveNoEffects() {
        val recovery=RecoveryState();recovery.start(1);recovery.connected(1,100)
        for(state in listOf(recovery.snapshot(),RecoveryState.Snapshot())) {
            assertFalse(SafeModeRuntimePolicy.apply(true,state,false,false,{fail("Healthy/foreign VPN cancelled")},{fail("Healthy/foreign VPN closed")}))
        }
        assertEquals(RecoveryState.Phase.CONNECTED,recovery.snapshot().phase)
    }
    @Test fun enablingAlsoCancelsInflightRecoveryAndRetainedOriginalTun() {
        for(phase in listOf(RecoveryState.Phase.RECONNECTING,RecoveryState.Phase.BLOCKED,RecoveryState.Phase.BACKOFF)) {
            var cancellations=0;var releases=0
            assertTrue(SafeModeRuntimePolicy.apply(true,RecoveryState.Snapshot(phase=phase),false,false,{cancellations++},{releases++}))
            assertEquals(1,cancellations);assertEquals(1,releases)
        }
    }
    @Test fun disablingDoesNotResumeCancelledRecoveryOrTouchAnyTun() {
        val recovery=RecoveryState();recovery.start(1);recovery.cancel(2,RecoveryReason.CANCELLED)
        val before=recovery.snapshot()
        assertFalse(SafeModeRuntimePolicy.apply(false,before,true,true,{fail("Disable cancelled")},{fail("Disable touched TUN")}))
        assertEquals(before,recovery.snapshot());assertFalse(recovery.connected(2,3000))
    }
}
