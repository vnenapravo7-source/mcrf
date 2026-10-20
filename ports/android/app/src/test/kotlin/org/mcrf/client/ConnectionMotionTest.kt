package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ConnectionMotionTest {
    @Test fun waitingNeverBecomesConnectedOnATimer(){
        val m=ConnectionMotion();m.observe(ConnectionMotion.Signal.CONNECTING,100)
        for(now in listOf(100L,1000L,1000000L))assertEquals(ConnectionMotion.Phase.FORGING,m.frame(now).phase)
        m.observe(ConnectionMotion.Signal.CONNECTING,1200)
        assertEquals(1200L,m.frame(1300).age)
    }
    @Test fun onlyRealSuccessStartsEightShardScene(){
        val m=ConnectionMotion();m.observe(ConnectionMotion.Signal.CONNECTING,0);m.observe(ConnectionMotion.Signal.CONNECTED,1000)
        assertEquals(ConnectionMotion.Phase.BREAKING,m.frame(1400).phase)
        assertEquals(ConnectionMotion.Phase.CONNECTED,m.frame(1900).phase)
        m.observe(ConnectionMotion.Signal.CONNECTED,2300)
        assertEquals(500L,m.frame(2400).age)
    }
    @Test fun openingAnAlreadyConnectedAppDoesNotReplaySuccess(){
        val m=ConnectionMotion();m.observe(ConnectionMotion.Signal.CONNECTED,100)
        assertEquals(ConnectionMotion.Phase.CONNECTED,m.frame(100).phase)
        assertEquals(500L,m.frame(600).age)
    }
    @Test fun failureDeflectsThenFallsThenKeepsError(){
        val m=ConnectionMotion();m.observe(ConnectionMotion.Signal.CONNECTING,0);m.observe(ConnectionMotion.Signal.FAILED,100)
        assertEquals(ConnectionMotion.Phase.REJECTED,m.frame(400).phase)
        assertEquals(ConnectionMotion.Phase.FALLING,m.frame(1000).phase)
        assertEquals(ConnectionMotion.Phase.ERROR,m.frame(3200).phase)
        m.observe(ConnectionMotion.Signal.FAILED,6000)
        assertEquals(ConnectionMotion.Phase.ERROR,m.frame(6200).phase)
    }
    @Test fun stopCleanupToIdleDoesNotRestartTheFall(){
        val m=ConnectionMotion();m.observe(ConnectionMotion.Signal.CONNECTED,0);m.observe(ConnectionMotion.Signal.STOPPING,100)
        m.observe(ConnectionMotion.Signal.IDLE,500)
        assertEquals(700L,m.frame(800).age)
        assertEquals(ConnectionMotion.Phase.FALLING,m.frame(800).phase)
        assertEquals(ConnectionMotion.Phase.IDLE,m.frame(2300).phase)
    }
    @Test fun cancelAndRetryInterruptOnlyPresentation(){
        val m=ConnectionMotion();m.observe(ConnectionMotion.Signal.CONNECTING,0);m.observe(ConnectionMotion.Signal.IDLE,100)
        assertEquals(ConnectionMotion.Phase.FALLING,m.frame(200).phase)
        m.observe(ConnectionMotion.Signal.CONNECTING,300)
        assertEquals(ConnectionMotion.Phase.FORGING,m.frame(350).phase)
        assertEquals(50L,m.frame(350).age)
    }
    @Test fun zeroUnknownAndNegativeCountersNeverInventASpeed(){
        assertEquals(0f,ConnectionMotion.flow(0,0,true),0f)
        assertEquals(0f,ConnectionMotion.flow(1000,1000,false),0f)
        assertEquals(0f,ConnectionMotion.flow(-1,-10,true),0f)
        assertTrue(ConnectionMotion.flow(1024,0,true)>0)
        assertTrue(ConnectionMotion.flow(1024*1024,0,true)>ConnectionMotion.flow(1024,0,true))
        assertEquals(1f,ConnectionMotion.flow(Long.MAX_VALUE,Long.MAX_VALUE,true),0f)
    }
}
