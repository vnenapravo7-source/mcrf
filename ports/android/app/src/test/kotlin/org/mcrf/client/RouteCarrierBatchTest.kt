package org.mcrf.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import org.junit.Assert.*
import org.junit.Test

class RouteCarrierBatchTest {
    private class Owner:AutoCloseable {val closed=AtomicBoolean();override fun close(){closed.set(true)}}
    @Test fun preparationIsParallelBoundedAndMissingOptionalMemberDoesNotDiscardOthers() {
        val entered=CountDownLatch(4);val active=AtomicInteger();val peak=AtomicInteger()
        val result=RouteCarrierBatch.start((0..7).map(Int::toString),BooleanSupplier {false}){id,_->
            val count=active.incrementAndGet();peak.updateAndGet {maxOf(it,count)};entered.countDown()
            try {check(entered.await(5,TimeUnit.SECONDS));if(id=="7")null else Owner()}
            finally{active.decrementAndGet()}
        }
        assertEquals(7,result.size);assertEquals(4,peak.get());assertEquals(0,active.get())
        assertTrue(result.values.none {it.closed.get()});result.values.forEach(Owner::close)
    }
    @Test fun siblingFailureWaitsForStartedOwnerAndRetiresItBeforeThrowing() {
        val owner=Owner();val entered=CountDownLatch(2);val completed=AtomicBoolean()
        assertThrows(IllegalStateException::class.java){
            RouteCarrierBatch.start(listOf("owner","failure"),BooleanSupplier {false}){id,_->
                entered.countDown();check(entered.await(5,TimeUnit.SECONDS))
                if(id=="failure")throw IllegalStateException("fixture failure")
                completed.set(true);owner
            }
        }
        assertTrue(completed.get());assertTrue(owner.closed.get())
    }
    @Test fun cancellationDoesNotAbandonAnOwnerReturnedDuringCancellation() {
        val cancelled=AtomicBoolean();val owner=Owner()
        assertThrows(IllegalStateException::class.java){
            RouteCarrierBatch.start(listOf("owner"),BooleanSupplier {cancelled.get()}){_,_->cancelled.set(true);owner}
        }
        assertTrue(owner.closed.get())
    }
}
