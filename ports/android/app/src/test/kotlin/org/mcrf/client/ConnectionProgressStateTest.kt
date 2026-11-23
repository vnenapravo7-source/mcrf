package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ConnectionProgressStateTest {
    private fun phase(step:String="prepare",status:String="Подключение…",pending:Boolean=true,connected:Boolean=false,failed:Boolean=false,stopping:Boolean=false,paused:Boolean=false,challenge:Boolean=false)=
        ConnectionProgressState.resolve(status,step,pending,connected,failed,stopping,paused,challenge)
    @Test fun realMilestonesOwnProgressAndOnlyServiceSuccessCompletesIt() {
        assertEquals(ConnectionProgressState.Phase.PREPARING,phase())
        assertEquals(ConnectionProgressState.Phase.CONNECTING,phase("connect"))
        assertEquals(ConnectionProgressState.Phase.VERIFYING,phase("verify"))
        assertEquals(ConnectionProgressState.Phase.CONNECTED,phase("verify",pending=false,connected=true))
        assertEquals(ConnectionProgressState.Phase.IDLE,phase("verify",pending=false))
        assertEquals(ConnectionProgressState.Phase.FAILED,phase("verify",pending=false,failed=true))
        // Repeated renders and arbitrarily old startup state cannot synthesize success.
        repeat(1000){assertEquals(ConnectionProgressState.Phase.VERIFYING,phase("verify"))}
    }
    @Test fun recoveryPauseChallengeAndRetirementOverrideOrdinaryStartup() {
        assertEquals(ConnectionProgressState.Phase.RECOVERING,phase("verify",status="Восстановление соединения…"))
        assertEquals(ConnectionProgressState.Phase.PAUSED,phase(connected=true,paused=true))
        assertEquals(ConnectionProgressState.Phase.CHALLENGE,phase(challenge=true))
        assertEquals(ConnectionProgressState.Phase.STOPPING,phase(connected=true,challenge=true,stopping=true))
        assertFalse(ConnectionProgressState.Phase.CONNECTED.moving)
        assertFalse(ConnectionProgressState.Phase.IDLE.moving)
        assertTrue(ConnectionProgressState.Phase.RECOVERING.moving)
    }
}
