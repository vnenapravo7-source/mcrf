package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecoveryKillSwitchTest {
    @Test fun featureDefaultsAndOldOptOutPreserveUnifiedRecovery() {
        assertTrue(ClientBehaviorSettings().autoRecoveryEnabled)
        assertFalse(ClientBehaviorSettings().killSwitchBeta)
        val migrated=ClientBehaviorSettings.fromJson(JSONObject().put("reconnectOnLaunch",false))
        assertFalse(migrated.reconnectOnLaunch)
        assertFalse(migrated.autoRecoveryEnabled)
        val saved=migrated.copy(autoRecoveryEnabled=false,killSwitchBeta=true)
        assertEquals(saved,ClientBehaviorSettings.fromJson(saved.json()))
    }
    @Test fun mergePreservesEveryExplicitChoiceAndOneToggleControlsBothPaths() {
        for(launch in listOf(false,true))for(recovery in listOf(false,true)) {
            val value=ClientBehaviorSettings.fromJson(JSONObject().put("reconnectOnLaunch",launch).put("autoRecoveryEnabled",recovery))
            assertEquals(launch,value.reconnectOnLaunch);assertEquals(recovery,value.autoRecoveryEnabled)
            assertEquals(launch&&recovery,value.recoveryEnabled)
            for(on in listOf(false,true)) {
                val changed=ClientBehaviorSettings.fromJson(value.withRecovery(on).json())
                assertEquals(on,changed.reconnectOnLaunch);assertEquals(on,changed.autoRecoveryEnabled)
                assertEquals(on,changed.recoveryEnabled(RecoveryReason.ENGINE_CRASH))
            }
        }
    }
    @Test fun classificationsDoNotRetryAuthProtocolOrCancellationWhileOffline() {
        assertEquals(RecoveryReason.AUTHENTICATION,RecoveryPolicy.classify(java.io.IOException("unsupported",SecurityException("denied")),false))
        assertEquals(RecoveryReason.PROTOCOL,RecoveryPolicy.classify(java.net.ProtocolException("unsupported"),false))
        assertEquals(RecoveryReason.CANCELLED,RecoveryPolicy.classify(java.io.IOException("wrapped",InterruptedException()),false))
        assertEquals(RecoveryReason.NETWORK_LOSS,RecoveryPolicy.classify(java.io.IOException("unreachable"),false))
        val policy=RecoveryPolicy()
        for(reason in listOf(RecoveryReason.AUTHENTICATION,RecoveryReason.PROTOCOL,RecoveryReason.CANCELLED,RecoveryReason.MANUAL_STOP))assertFalse(policy.retryable(reason))
    }
    @Test fun cappedJitterAndAttemptBudgetSurviveShortSuccessfulReconnects() {
        val policy=RecoveryPolicy(maxAttempts=3,initialDelayMs=100,maxDelayMs=200,maxElapsedMs=2000,stableConnectionMs=1000)
        val state=RecoveryState(policy);state.start(1)
        var now=1L
        repeat(3) { index ->
            assertTrue(state.fail(1,RecoveryReason.ENGINE_CRASH,now))
            val delay=state.schedule(1,now,1.0)!!
            assertEquals(if(index==0)100L else 200L,delay)
            now+=delay;assertTrue(state.attempt(1,now));assertTrue(state.connected(1,now));now++
        }
        assertTrue(state.fail(1,RecoveryReason.SERVER_FAILURE,now))
        assertNull(state.schedule(1,now,0.0))
        assertEquals(3,state.snapshot().attempt)
    }
    @Test fun stableConnectionRenewsBudgetAndDeadlineCannotQueueAnotherRetry() {
        val policy=RecoveryPolicy(maxAttempts=2,initialDelayMs=100,maxDelayMs=100,maxElapsedMs=300,stableConnectionMs=100)
        val state=RecoveryState(policy);state.start(3)
        state.fail(3,RecoveryReason.NETWORK_LOSS,0);assertNotNull(state.schedule(3,0,1.0))
        assertTrue(state.attempt(3,100));state.connected(3,100)
        state.fail(3,RecoveryReason.NETWORK_LOSS,201)
        assertNotNull(state.schedule(3,201,1.0));assertEquals(1,state.snapshot().attempt)
        assertFalse(state.attempt(3,501))
    }
    @Test fun explicitStopAndNewStartRejectStaleTimersAndCallbacks() {
        val state=RecoveryState();state.start(10)
        state.fail(10,RecoveryReason.NETWORK_LOSS,0);state.schedule(10,0,0.0)
        state.cancel(11,RecoveryReason.MANUAL_STOP)
        assertFalse(state.attempt(10,2000));assertFalse(state.connected(10,2000))
        assertFalse(state.snapshot().userRequested)
        state.start(12)
        assertFalse(state.fail(10,RecoveryReason.ENGINE_CRASH,3000))
        assertTrue(state.connected(12,3000))
    }
    @Test fun killSwitchRequiresOptInOwnedVpnAndUserIntent() {
        assertFalse(KillSwitchPolicy.DEFAULT_ENABLED)
        assertTrue(KillSwitchPolicy.hold(true,true,true,RecoveryReason.ENGINE_CRASH))
        assertFalse(KillSwitchPolicy.hold(false,true,true,RecoveryReason.NETWORK_LOSS))
        assertFalse(KillSwitchPolicy.hold(true,false,true,RecoveryReason.NETWORK_LOSS))
        assertFalse(KillSwitchPolicy.hold(true,true,false,RecoveryReason.NETWORK_LOSS))
        for(reason in listOf(RecoveryReason.MANUAL_STOP,RecoveryReason.ANDROID_REVOKE,RecoveryReason.CANCELLED))
            assertFalse(KillSwitchPolicy.hold(true,true,true,reason))
        val state=RecoveryState();state.start(1);state.finish(1,true)
        assertEquals(RecoveryState.Phase.BLOCKED,state.snapshot().phase)
        state.cancel(2,RecoveryReason.MANUAL_STOP);assertFalse(state.snapshot().trafficBlocked)
    }
}
