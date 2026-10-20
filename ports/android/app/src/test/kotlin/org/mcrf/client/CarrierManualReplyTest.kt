package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierManualReplyTest {
    @Test fun realGlobalDeliverySaturationRetainsManualReplyUntilOriginalPoolRecovers(){
        val started=java.util.concurrent.CountDownLatch(4);val release=java.util.concurrent.CountDownLatch(1);val finished=java.util.concurrent.CountDownLatch(4)
        val blockers=mutableListOf<String>();var manual:String?=null
        try {
            repeat(4){index->
                val id=requireNotNull(CarrierChallenge.registerBounded("owned-busy-$index","https://vk.com/captcha",{started.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);finished.countDown()}))
                blockers.add(id);assertTrue(CarrierChallenge.respondAsync(id,"owned-blocker-proof"))
            }
            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS))
            val received=java.util.concurrent.CompletableFuture<String>();val calls=java.util.concurrent.atomic.AtomicInteger()
            val id=requireNotNull(CarrierChallenge.registerBounded("owned-manual","https://vk.com/captcha",{calls.incrementAndGet();received.complete(it)}));manual=id
            val reply=CarrierManualReply({CarrierChallenge.get(id)!=null},{CarrierChallenge.respondAsync(id,it)})
            assertTrue(reply.accept("owned-manual-proof"));repeat(3){assertEquals(CarrierManualReply.State.WAITING,reply.attempt())}
            assertTrue(reply.hasPending());assertFalse(received.isDone);assertNotNull(CarrierChallenge.get(id))
            release.countDown();assertTrue(finished.await(2,java.util.concurrent.TimeUnit.SECONDS))
            val deadline=System.nanoTime()+2_000_000_000L
            var state=reply.attempt()
            while(state==CarrierManualReply.State.WAITING&&System.nanoTime()<deadline){Thread.sleep(5);state=reply.attempt()}
            assertEquals(CarrierManualReply.State.SCHEDULED,state)
            assertEquals("owned-manual-proof",received.get(2,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(reply.hasPending());assertEquals(CarrierManualReply.State.EMPTY,reply.attempt());assertEquals(1,calls.get())
        }finally{release.countDown();blockers.forEach(CarrierChallenge::remove);manual?.let(CarrierChallenge::remove)}
    }
    @Test fun busyExecutorRetainsExactlyOneProofUntilScheduledOnce(){
        var accepts=false;val offered=mutableListOf<String>()
        val reply=CarrierManualReply({true},{offered.add(it);accepts})
        assertTrue(reply.accept("owned-first"));assertFalse(reply.accept("owned-replacement"))
        repeat(3){assertEquals(CarrierManualReply.State.WAITING,reply.attempt())}
        accepts=true;assertEquals(CarrierManualReply.State.SCHEDULED,reply.attempt())
        assertEquals(CarrierManualReply.State.EMPTY,reply.attempt());assertFalse(reply.accept("owned-later"))
        assertEquals(List(4){"owned-first"},offered)
    }
    @Test fun expiryBeforeCapacityReturnsNeverQueuesLateProof(){
        var alive=true;var calls=0
        val reply=CarrierManualReply({alive},{calls++;false})
        reply.accept("owned-proof");assertEquals(CarrierManualReply.State.WAITING,reply.attempt())
        alive=false;assertEquals(CarrierManualReply.State.EXPIRED,reply.attempt())
        assertEquals(CarrierManualReply.State.EMPTY,reply.attempt());assertEquals(1,calls)
        assertFalse(reply.accept("owned-new-proof"))
    }
    @Test fun destructionOrCancelClearsProofWithoutSendingIt(){
        val reply=CarrierManualReply({true},{fail("Cleared proof must not be sent");true})
        reply.accept("owned-proof");reply.clear()
        assertEquals(CarrierManualReply.State.EMPTY,reply.attempt());assertFalse(reply.accept("owned-late"))
        assertFalse(reply.toString().contains("owned-proof"))
    }
    @Test fun invalidValuesNeverReplaceAValidPendingProof(){
        val offered=mutableListOf<String>();val reply=CarrierManualReply({true},{offered.add(it);true})
        for(proof in listOf("","error:cancelled","ERROR:timeout","owned|other","owned\nother","x".repeat(16385)))assertFalse(reply.accept(proof))
        assertTrue(reply.accept("owned-valid"));assertEquals(CarrierManualReply.State.SCHEDULED,reply.attempt())
        assertEquals(listOf("owned-valid"),offered)
    }
    @Test fun localDispatchFailureDoesNotLoseOrAcknowledgeProof(){
        var calls=0;val reply=CarrierManualReply({true},{calls++;if(calls==1)throw java.util.concurrent.RejectedExecutionException();true})
        reply.accept("owned-proof");assertEquals(CarrierManualReply.State.WAITING,reply.attempt())
        assertEquals(CarrierManualReply.State.SCHEDULED,reply.attempt());assertEquals(2,calls)
    }
}
