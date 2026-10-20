package org.mcrf.client

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class DrainingCallbackExecutorTest {
    @Test fun normalCallbackRunsExactlyOnce(){val worker=Executors.newSingleThreadExecutor();try{val done=CountDownLatch(1);val calls=AtomicInteger();DrainingCallbackExecutor(worker).execute{calls.incrementAndGet();done.countDown()};assertTrue(done.await(2,TimeUnit.SECONDS));assertEquals(1,calls.get())}finally{worker.shutdown()}}
    @Test fun lateCallbackAfterShutdownIsNotRejectedOrLost(){val worker=Executors.newSingleThreadExecutor();worker.shutdown();val calls=AtomicInteger();DrainingCallbackExecutor(worker).execute{calls.incrementAndGet()};assertEquals(1,calls.get())}
    @Test fun directExecutorIsNotDuplicated(){val calls=AtomicInteger();DrainingCallbackExecutor(Executor{it.run()}).execute{calls.incrementAndGet()};assertEquals(1,calls.get())}
}
