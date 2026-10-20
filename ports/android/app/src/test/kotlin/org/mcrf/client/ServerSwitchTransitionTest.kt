package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ServerSwitchTransitionTest {
    @Test fun waitsForOwnedStopAcknowledgementEvenIfServiceLooksIdle() {
        val q=ServerSwitchTransition();val t=q.request("bs",true,100)
        assertEquals(ServerSwitchTransition.Next.WAIT,q.poll(101,false,false,"bs",true))
        ServerSwitchTransition.observeCommand(t.token)
        assertEquals(ServerSwitchTransition.Next.READY,q.poll(102,false,false,"bs",true))
        assertEquals(ServerSwitchTransition.Next.NONE,q.poll(103,false,false,"bs",true))
    }
    @Test fun activeServiceAndWorkerRetirementBothBlockValidation() {
        val q=ServerSwitchTransition();val t=q.request("vpn",false,100);ServerSwitchTransition.observeCommand(t.token)
        assertEquals(ServerSwitchTransition.Next.WAIT,q.poll(101,true,false,"vpn",true))
        assertEquals(ServerSwitchTransition.Next.WAIT,q.poll(102,false,true,"vpn",true))
        assertEquals(ServerSwitchTransition.Next.READY,q.poll(103,false,false,"vpn",true))
    }
    @Test fun laterNotificationStopOrRevocationCancelsInsteadOfRestarting() {
        val q=ServerSwitchTransition();val t=q.request("vpn",false,100);ServerSwitchTransition.observeCommand(t.token)
        ServerSwitchTransition.observeCommand(null)
        assertEquals(ServerSwitchTransition.Next.CANCELLED,q.poll(101,false,false,"vpn",true))
        assertNull(q.target)
    }
    @Test fun unrelatedFirstCommandCannotSatisfyOurStop() {
        val q=ServerSwitchTransition();q.request("vpn",false,100);ServerSwitchTransition.observeCommand("other")
        assertEquals(ServerSwitchTransition.Next.CANCELLED,q.poll(101,false,false,"vpn",true))
    }
    @Test fun backgroundWindowCannotLaunchNewConnection() {
        val q=ServerSwitchTransition();val t=q.request("bs",true,100);ServerSwitchTransition.observeCommand(t.token)
        assertEquals(ServerSwitchTransition.Next.WAIT,q.poll(101,false,false,"bs",false))
        assertEquals(ServerSwitchTransition.Next.READY,q.poll(102,false,false,"bs",true))
    }
    @Test fun timeoutClockRollbackAndExplicitCancelNeverReplay() {
        val q=ServerSwitchTransition();q.request("bs",true,100)
        assertEquals(ServerSwitchTransition.Next.TIMED_OUT,q.poll(30_100,false,false,"bs",true))
        q.request("bs",true,100);assertEquals(ServerSwitchTransition.Next.TIMED_OUT,q.poll(99,false,false,"bs",true))
        q.request("bs",true,100);q.cancel();assertEquals(ServerSwitchTransition.Next.NONE,q.poll(101,false,false,"bs",true))
    }
    @Test fun changedSelectionRetiresPendingRequest() {
        val q=ServerSwitchTransition();q.request("bs",true,100)
        assertEquals(ServerSwitchTransition.Next.CANCELLED,q.poll(101,false,false,"vpn",true))
    }
    @Test fun productionKeepsNativeValidationAndObservesStopStartRevoke() {
        fun source(name:String)=listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first {it.isFile}.readText()
        val service=source("McrfVpnService")
        assertTrue(service.contains("ServerSwitchTransition.observeCommand(intent?.getStringExtra(ServerSwitchTransition.EXTRA))"))
        assertEquals(2,Regex("ServerSwitchTransition.observeCommand\\(null\\)").findAll(service).count())
        val main=source("MainActivity")
        assertTrue(main.contains("ServerSwitchTransition.Next.READY->prepareConnection(false,requestedWhitelists=target.whitelists)"))
        assertTrue(main.contains("ClientPrimarySelection.validateNative(this,prepared.toString(),vpn)"))
        assertTrue(main.contains("serverSwitch.cancel();handler.removeCallbacks(serverSwitchPoll)"))
    }
}
