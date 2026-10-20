package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CarrierChallengeBrowserOwnerTest {
    @Test fun duplicateAndOtherRequestCannotReplaceLiveBrowser() {
        var replies=0
        val first=requireNotNull(CarrierChallenge.registerBounded("first","https://vk.com/captcha",{replies++}))
        val second=requireNotNull(CarrierChallenge.registerBounded("second","https://vk.com/captcha",{replies++}))
        try {
            assertTrue(CarrierChallenge.claimBrowser(first))
            assertFalse(CarrierChallenge.claimBrowser(first))
            assertFalse(CarrierChallenge.claimBrowser(second))
            CarrierChallenge.releaseBrowser(second)
            assertFalse(CarrierChallenge.claimBrowser(second))
            CarrierChallenge.releaseBrowser(first)
            assertTrue(CarrierChallenge.claimBrowser(second))
            assertEquals(0,replies)
        }finally{CarrierChallenge.remove(first);CarrierChallenge.remove(second)}
    }
    @Test fun expiryAndRemovalReleaseOwnershipWithoutAnswering() {
        var replies=0
        val first=requireNotNull(CarrierChallenge.registerBounded("first","https://vk.com/captcha",{replies++},{},9000))
        val second=requireNotNull(CarrierChallenge.registerBounded("second","https://vk.com/captcha",{replies++}))
        try {
            assertTrue(CarrierChallenge.claimBrowser(first))
            val request=requireNotNull(CarrierChallenge.get(first))
            CarrierChallenge.expire(request.created+9_000_000_001L)
            assertFalse(CarrierChallenge.claimBrowser(first))
            assertTrue(CarrierChallenge.claimBrowser(second))
            CarrierChallenge.remove(second)
            assertFalse(CarrierChallenge.claimBrowser(second))
            assertEquals(0,replies)
        }finally{CarrierChallenge.remove(first);CarrierChallenge.remove(second)}
    }
    @Test fun simultaneousNotificationTapsHaveExactlyOneOwner() {
        val id=requireNotNull(CarrierChallenge.registerBounded("owned","https://vk.com/captcha",{}))
        val workers=Executors.newFixedThreadPool(8)
        val start=CountDownLatch(1);val done=CountDownLatch(24);val claims=AtomicInteger()
        try {
            repeat(24){workers.submit {try{start.await();if(CarrierChallenge.claimBrowser(id))claims.incrementAndGet()}finally{done.countDown()}}}
            start.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(1,claims.get())
        }finally{workers.shutdownNow();CarrierChallenge.remove(id)}
    }
}
