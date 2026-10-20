package org.mcrf.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CarrierChallengeDeliveryTest {
    @Test fun blockedWritesNeverRunOnCallerAndCapacityIsBoundedWithoutQueue() {
        val delivery=CarrierChallengeDelivery()
        val started=CountDownLatch(4);val release=CountDownLatch(1);val finished=CountDownLatch(4)
        val caller=Thread.currentThread()
        val wrongThread=java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            repeat(4){assertTrue(delivery.offer {if(Thread.currentThread()===caller)wrongThread.set(true);started.countDown();release.await(3,TimeUnit.SECONDS);finished.countDown()})}
            assertTrue(started.await(2,TimeUnit.SECONDS))
            assertFalse(delivery.offer {fail("No unbounded overflow queue")})
            assertFalse(wrongThread.get())
        }finally{release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));delivery.close()}
        assertFalse(delivery.offer {})
    }
}
