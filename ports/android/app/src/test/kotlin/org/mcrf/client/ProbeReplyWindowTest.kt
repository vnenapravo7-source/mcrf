package org.mcrf.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProbeReplyWindowTest {
    @Test fun openWindowDeliversProgress(){
        val window=ProbeReplyWindow();var count=0
        assertTrue(window.deliver {count++});assertTrue(window.deliver {count++})
        assertEquals(2,count)
    }
    @Test fun closedWindowNeverTouchesReplacementProgress(){
        val old=ProbeReplyWindow();old.close()
        val replacement=ProbeReplyWindow();var progress="new"
        assertFalse(old.deliver {progress="old"})
        assertTrue(replacement.deliver {progress="new-complete"})
        assertEquals("new-complete",progress)
    }
    @Test fun terminalCallbackCanSealItsOwnWindow(){
        val window=ProbeReplyWindow();var count=0
        assertTrue(window.deliver {count++;window.close()})
        assertFalse(window.deliver {count++});assertEquals(1,count)
    }
    @Test fun repeatedCloseDoesNotReopenWindow(){
        val window=ProbeReplyWindow();repeat(3){window.close()}
        assertFalse(window.deliver {fail("late callback")})
    }
    @Test fun closeWaitsForAdmittedCallbackBeforeProgressReset(){
        val window=ProbeReplyWindow();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val closing=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        var progress="initial"
        try {
            val callback=pool.submit<Boolean>{window.deliver {entered.countDown();check(release.await(5,TimeUnit.SECONDS));progress="old"}}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val closed=pool.submit {closing.countDown();window.close();progress="replacement"}
            assertTrue(closing.await(5,TimeUnit.SECONDS));assertFalse(closed.isDone)
            release.countDown();assertTrue(callback.get(5,TimeUnit.SECONDS));closed.get(5,TimeUnit.SECONDS)
            assertEquals("replacement",progress)
            assertFalse(window.deliver {progress="late"});assertEquals("replacement",progress)
        }finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun callbackFailureStillAllowsFinallyToClose(){
        val window=ProbeReplyWindow()
        try {window.deliver {throw IllegalArgumentException("fixture")};fail("exception expected")}
        catch(_:IllegalArgumentException){}finally {window.close()}
        assertFalse(window.deliver {fail("late callback")})
    }
}
