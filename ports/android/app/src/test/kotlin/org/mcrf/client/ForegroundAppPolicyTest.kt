package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ForegroundAppPolicyTest {
    @Test fun unreliableMissingStaleAndFutureSignalsKeepCommonBehavior() {
        val base=ForegroundAppPolicy.Mode.DEFAULT
        val signals=listOf(null,ForegroundAppPolicy.Signal("org.example.call",true,10,false),
            ForegroundAppPolicy.Signal("org.example.call",true,0,true),ForegroundAppPolicy.Signal("org.example.call",false,10_001,true))
        signals.forEach {assertEquals(base,ForegroundAppPolicy.mode(base,it,10_000,ForegroundAppPolicy.Mode.REALTIME,ForegroundAppPolicy.Mode.ECONOMY))}
        assertNull(ForegroundAppPolicy.androidSignal())
    }
    @Test fun onlyFreshReliableAndroidOwnershipChangesMode() {
        val signal=ForegroundAppPolicy.Signal("org.example.call",true,9_000,true)
        assertEquals(ForegroundAppPolicy.Mode.REALTIME,ForegroundAppPolicy.mode(ForegroundAppPolicy.Mode.DEFAULT,signal,10_000,ForegroundAppPolicy.Mode.REALTIME,ForegroundAppPolicy.Mode.ECONOMY))
        assertEquals(ForegroundAppPolicy.Mode.ECONOMY,ForegroundAppPolicy.mode(ForegroundAppPolicy.Mode.DEFAULT,signal.copy(foreground=false),10_000,ForegroundAppPolicy.Mode.REALTIME,ForegroundAppPolicy.Mode.ECONOMY))
    }
}
