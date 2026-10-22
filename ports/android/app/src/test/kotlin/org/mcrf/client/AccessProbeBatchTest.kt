package org.mcrf.client
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test
class AccessProbeBatchTest {
    @Test fun hostsReallyRunInParallel(){
        val pool=Executors.newFixedThreadPool(2);val entered=CountDownLatch(2)
        try{val result=AccessProbeBatch(pool,500).run(2,{false},{}){
            entered.countDown();if(entered.await(300,TimeUnit.MILLISECONDS))AccessProbeBatch.State.RESPONSE else AccessProbeBatch.State.NO_RESPONSE
        };assertEquals(List(2){AccessProbeBatch.State.RESPONSE},result)}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(1,TimeUnit.SECONDS))}
    }
    @Test fun oneSharedDeadlineCancelsBothRequestsAndClosesResources(){
        val pool=Executors.newFixedThreadPool(2);var closed=false;val start=System.nanoTime()
        try{val result=AccessProbeBatch(pool,80).run(2,{false},{closed=true}){Thread.sleep(5000);AccessProbeBatch.State.RESPONSE}
            assertTrue(closed);assertEquals(List(2){AccessProbeBatch.State.NO_RESPONSE},result)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000)
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(1,TimeUnit.SECONDS))}
    }
    @Test fun exhaustedExecutorDoesNotMeanBothSitesAreBlocked(){
        val pool=Executors.newSingleThreadExecutor();pool.shutdown()
        assertEquals(List(2){AccessProbeBatch.State.UNAVAILABLE},AccessProbeBatch(pool,30).run(2,{false},{}){error("never")})
    }
    @Test fun cancellationCannotBecomeEvidenceForBs(){
        val pool=Executors.newFixedThreadPool(2)
        try{assertEquals(List(2){AccessProbeBatch.State.CANCELLED},AccessProbeBatch(pool,80).run(2,{true},{}){error("never")})}finally{pool.shutdownNow()}
    }
}
