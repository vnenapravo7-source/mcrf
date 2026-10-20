package org.mcrf.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CarrierChallengeAsyncTest {
    @Test fun nativeReplyRunsOffCallerAndExactRequestStillRepliesOnce() {
        val caller=Thread.currentThread()
        val done=CountDownLatch(1);val calls=AtomicInteger();val onCaller=java.util.concurrent.atomic.AtomicBoolean(false)
        val id=requireNotNull(CarrierChallenge.registerBounded("owned-async","https://vk.com/captcha",{
            onCaller.set(Thread.currentThread()===caller);calls.incrementAndGet();done.countDown()
        }))
        try {
            assertTrue(CarrierChallenge.respondAsync(id,"owned-proof"))
            assertTrue(done.await(2,TimeUnit.SECONDS))
            assertTrue(CarrierChallenge.respondAsync(id,"duplicate-proof"))
            assertNull(CarrierChallenge.get(id));assertEquals(1,calls.get());assertFalse(onCaller.get())
        }finally{CarrierChallenge.remove(id)}
    }
    @Test fun expiredOrInvalidProofIsNeverDispatched() {
        val calls=AtomicInteger()
        val id=requireNotNull(CarrierChallenge.registerBounded("owned-expired","https://vk.com/captcha",{calls.incrementAndGet()},timeoutMillis=24000))
        val request=requireNotNull(CarrierChallenge.get(id))
        assertFalse(CarrierChallenge.respondAsync(id,"invalid|proof"))
        CarrierChallenge.expire(request.created+24_000_000_001L)
        assertTrue(CarrierChallenge.respondAsync(id,"late-proof"))
        assertEquals(0,calls.get());assertNull(CarrierChallenge.get(id))
    }
}
