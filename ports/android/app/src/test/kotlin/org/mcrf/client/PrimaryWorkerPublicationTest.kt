package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Publication model only. No Binder/native death or resource-release proof. */
class PrimaryWorkerPublicationTest {
    @Test fun lateRepliesCannotRestoreStatusOrPortsAfterStopOrDeath() {
        for(dead in listOf(false,true)) {
            val owner=PrimaryWorkerPublication()
            assertTrue(owner.publish("ROUTE_LISTENING",routePort=12345){true})
            if(dead)assertTrue(owner.retire()) else assertTrue(owner.stop())
            for(status in listOf("READY","PACKET_PIPE_STARTED","PROBING","RUNNING"))assertFalse(owner.publish(status){true})
            assertFalse(owner.publish("ROUTE_LISTENING",routePort=12346){true})
            assertEquals(0,owner.snapshot.routePort);assertEquals(0,owner.snapshot.proxyPort)
            assertEquals(if(dead)"STOPPED" else "STOPPING",owner.snapshot.status)
        }
    }
    @Test fun stopNeverDowngradesObservedDeathAndDeadListenerNeverPublishes() {
        val owner=PrimaryWorkerPublication()
        assertFalse(owner.publish("RUNNING"){false})
        assertEquals("BOUND",owner.snapshot.status)
        assertTrue(owner.retire());assertFalse(owner.retire());assertFalse(owner.stop())
        assertEquals("STOPPED",owner.snapshot.status)
    }
    @Test fun publicationAndTerminalTransitionAreSerialized() {
        val owner=PrimaryWorkerPublication();val entered=CountDownLatch(1);val leave=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>();val result=AtomicReference<Boolean?>()
        val publisher=Thread {try {result.set(owner.publish("STARTING",proxyPort=23456){entered.countDown();check(leave.await(2,TimeUnit.SECONDS));true})}catch(error:Throwable){failure.set(error)}}
        publisher.start();assertTrue(entered.await(2,TimeUnit.SECONDS))
        val stopper=Thread {owner.stop()};stopper.start();leave.countDown()
        publisher.join(3000);stopper.join(3000)
        assertFalse(publisher.isAlive);assertFalse(stopper.isAlive)
        assertNull(failure.get());assertEquals(true,result.get())
        assertEquals(PrimaryWorkerPublication.Snapshot("STOPPING"),owner.snapshot)
        assertFalse(owner.publish("PACKET_PIPE_STARTED"){true})
    }
}
