package org.mcrf.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CarrierCaptchaLogDeliveryTest {
    @Test fun blockedDiskKeepsCallerFreeAndBoundsQueueWhilePreservingOrder(){
        val delivery=CarrierCaptchaLogDelivery();val started=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(33)
        val caller=Thread.currentThread();val order=java.util.Collections.synchronizedList(mutableListOf<Int>())
        val callerUsed=java.util.concurrent.atomic.AtomicBoolean()
        try {
            assertTrue(delivery.offer {if(Thread.currentThread()===caller)callerUsed.set(true);started.countDown();release.await(5,TimeUnit.SECONDS);order.add(0);finished.countDown()})
            assertTrue(started.await(2,TimeUnit.SECONDS))
            repeat(32){index->assertTrue(delivery.offer {order.add(index+1);finished.countDown()})}
            assertFalse(delivery.offer {fail("Overflow must not execute or retain an unbounded queue")})
            assertFalse(callerUsed.get());assertTrue(order.isEmpty())
            delivery.close() // Must return even while the only disk worker remains blocked.
            assertFalse(delivery.offer {})
        }finally{release.countDown();assertTrue(finished.await(3,TimeUnit.SECONDS));delivery.close()}
        assertEquals((0..32).toList(),order)
    }
    @Test fun loggingFailureCannotStopFollowingRecordsOrReachCaller(){
        val delivery=CarrierCaptchaLogDelivery();val finished=CountDownLatch(1)
        try {
            assertTrue(delivery.offer {throw java.io.IOException("owned-private-diagnostic-failure")})
            assertTrue(delivery.offer {finished.countDown()})
            assertTrue(finished.await(2,TimeUnit.SECONDS))
        }finally{delivery.close()}
    }
}
