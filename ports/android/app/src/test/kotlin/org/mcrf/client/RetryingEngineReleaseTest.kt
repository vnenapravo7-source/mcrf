package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Injected release operation, NOT a real Android kernel failure receipt. */
class RetryingEngineReleaseTest {
    @Test fun failedCallerCannotForgetTheOnlyLeaseReleaseOwner() {
        val ready=CountDownLatch(1);val completed=CountDownLatch(1);val calls=AtomicInteger()
        val owner=RetryingEngineRelease {
            calls.incrementAndGet()
            if(ready.count>0)throw IOException("owned injected failure")
            completed.countDown()
        }
        try {
            assertThrows(IOException::class.java){owner.close()}
            ready.countDown()
            assertTrue(completed.await(5,TimeUnit.SECONDS))
            assertTrue(calls.get()>=2)
            owner.close() // Direct retry must also remain safe after retirement.
        }finally{ready.countDown();completed.await(5,TimeUnit.SECONDS)}
    }
    @Test fun successfulReleaseDoesNotCreateABackgroundAttempt() {
        val calls=AtomicInteger();RetryingEngineRelease {calls.incrementAndGet();Unit}.close()
        assertEquals(1,calls.get())
    }
}
