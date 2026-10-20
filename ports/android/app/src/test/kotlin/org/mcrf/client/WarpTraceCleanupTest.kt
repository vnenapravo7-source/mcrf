package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the exact shared retirement helper used by WarpTraceService; no native/phone claim. */
class WarpTraceCleanupTest {
    @Test fun completionFailureKeepsAdmissionAndRetainedOwnerUntilSuccessfulRetry() {
        val owner=Any();var retained:Any?=owner;var admitted=false;var ready=false
        val batch=ClientPrimaryRetirement(owner,{}, {}, {},emptyList(),{
            if(!ready)throw IOException("completion pending")
            retained=null;admitted=true
        })
        try {
            assertFalse(batch.attempt());assertSame(owner,retained);assertFalse(admitted)
            assertTrue(ClientPrimaryRetirement.hasPending())
            ready=true;assertTrue(batch.attempt())
            assertTrue(batch.complete);assertNull(retained);assertTrue(admitted)
            assertFalse(ClientPrimaryRetirement.hasPending())
        } finally {ready=true;batch.attempt()}
    }
    @Test fun pendingCloseRetainsExactOwnerAndBlocksCompletionAndNewAdmission() {
        val owner=Any();var retained:Any?=owner;var closeCalls=0;var runtimeClosed=false;var finished=false
        val batch=ClientPrimaryRetirement(owner,{}, {}, { actual ->
            assertSame(owner,actual);closeCalls++;if(closeCalls==1)throw IOException("pending death")
        },listOf({runtimeClosed=true}),{retained=null;finished=true})
        try {
            assertFalse(batch.attempt());assertSame(owner,retained);assertSame(owner,batch.owner)
            assertTrue(ClientPrimaryRetirement.hasPending());assertFalse(runtimeClosed);assertFalse(finished)
            assertTrue(batch.attempt());assertNull(retained);assertTrue(runtimeClosed);assertTrue(finished)
        } finally {batch.attempt()}
    }
    @Test fun dependentFailureDoesNotRepeatAcknowledgedOwnerCloseOrFinishSession() {
        val order=mutableListOf<String>();var ready=false;var finish=0
        val batch=ClientPrimaryRetirement(Any(),{order.add("stop")},{order.add("callbacks")},{order.add("death")},
            listOf({order.add("runtime");if(!ready)throw IOException("runtime pending")},{order.add("input")}),{finish++})
        try {
            assertFalse(batch.attempt());assertEquals(listOf("stop","callbacks","death","runtime"),order);assertEquals(0,finish)
            ready=true;assertTrue(batch.attempt());assertEquals(listOf("stop","callbacks","death","runtime","runtime","input"),order)
            assertTrue(batch.attempt());assertEquals(1,finish)
        } finally {ready=true;batch.attempt()}
    }
    @Test fun sessionCannotFinishOrAdmitReplacementWhileDeathIsPending() {
        val session=WarpTraceSession();val first="00000000-0000-0000-0000-000000000001";val next="00000000-0000-0000-0000-000000000002"
        assertTrue(session.begin(first));session.cancel(first);var dead=false
        val batch=ClientPrimaryRetirement(Any(),{}, {}, {if(!dead)throw IOException("not dead")},emptyList(),{session.finish(first)})
        try {
            assertFalse(batch.attempt());assertEquals(first,session.activeId());assertFalse(session.begin(next))
            dead=true;assertTrue(batch.attempt());assertNull(session.activeId());assertTrue(session.begin(next))
        } finally {dead=true;batch.attempt();session.close()}
    }
    @Test fun gracefulExecutorShutdownKeepsRetirementRetryAliveUntilCleanupCompletes() {
        val worker=Executors.newSingleThreadExecutor();val pending=CountDownLatch(1);val permit=CountDownLatch(1);val done=CountDownLatch(1)
        var first=true
        val batch=ClientPrimaryRetirement(Any(),{}, {}, {if(first){first=false;throw IOException("pending")}},emptyList(),{done.countDown()})
        try {
            worker.execute {
                if(!batch.attempt()){pending.countDown();permit.await();batch.attempt()}
            }
            assertTrue(pending.await(3,TimeUnit.SECONDS));worker.shutdown()
            assertFalse(worker.isTerminated);assertFalse(batch.complete);assertEquals(1L,done.count)
            permit.countDown();assertTrue(done.await(3,TimeUnit.SECONDS));assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS))
        } finally {permit.countDown();worker.shutdown();worker.awaitTermination(3,TimeUnit.SECONDS);batch.attempt()}
    }
}
