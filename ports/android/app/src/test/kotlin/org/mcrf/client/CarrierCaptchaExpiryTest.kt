package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierCaptchaExpiryTest {
    @Test fun unopenedBackgroundRequestExpiresWithoutActivityPolling() {
        val expired=java.util.concurrent.CountDownLatch(1)
        val replies=java.util.concurrent.atomic.AtomicInteger(0)
        val id=requireNotNull(CarrierChallenge.registerBounded("4-1","https://vk.com/captcha",{replies.incrementAndGet()},timeoutMillis=20,expired={expired.countDown()}))
        try {
            assertTrue(expired.await(2,java.util.concurrent.TimeUnit.SECONDS))
            assertNull(CarrierChallenge.get(id));assertEquals(0,replies.get())
        }finally {CarrierChallenge.remove(id)}
    }
    @Test fun expiredCancellationIsOnlyAStaticErrorAndNeverRunsOnCallerThread() {
        val thread=Thread.currentThread()
        val reply=java.util.concurrent.CompletableFuture<Pair<Thread,String>>()
        assertTrue(CarrierChallenge.cancelExpiredScopedAsync("wdtt","4-2",{reply.complete(Thread.currentThread() to CarrierChallenge.command("4-2",it))}))
        val result=reply.get(2,java.util.concurrent.TimeUnit.SECONDS)
        assertNotSame(thread,result.first)
        assertEquals("CAPTCHA_RESULT|4-2|error:timeout\n",result.second)
        assertFalse(CarrierChallenge.cancelExpiredScopedAsync("csqtt","",{fail("untagged cancellation must not be sent")}))
    }
    @Test fun boundedExpiryRetiresOnceWithoutAcceptingAnyLateProof() {
        var expired=0;var retired=0;var proofs=0
        val id=requireNotNull(CarrierChallenge.registerBounded("1-1","https://vk.com/captcha",{proofs++},{retired++},expired={expired++}))
        val request=requireNotNull(CarrierChallenge.get(id))
        try {
            CarrierChallenge.expire(request.created+150_000_000_001L)
            CarrierChallenge.expire(request.created+150_000_000_002L)
            CarrierChallenge.respond(id,"late-proof")
            assertNull(CarrierChallenge.get(id));assertEquals(1,expired);assertEquals(1,retired);assertEquals(0,proofs)
        }finally {CarrierChallenge.remove(id)}
    }
    @Test fun normalAnswerAndStopNeverTriggerExpiryCancellation() {
        var expired=0
        val answered=requireNotNull(CarrierChallenge.registerBounded("1-2","https://vk.com/captcha",{},expired={expired++}))
        val stopped=requireNotNull(CarrierChallenge.registerBounded("1-3","https://vk.com/captcha",{},expired={expired++}))
        CarrierChallenge.respond(answered,"fixture-proof");CarrierChallenge.remove(stopped)
        assertEquals(0,expired)
    }
    @Test fun onlyKnownExactWaiterProtocolsCanReceiveExpiryCancellation() {
        assertTrue(CarrierChallenge.scopedExpirySupported("wdtt","2-19"))
        assertTrue(CarrierChallenge.scopedExpirySupported("qwdtt","a".repeat(32)))
        assertTrue(CarrierChallenge.scopedExpirySupported("csqtt","a".repeat(48)))
        for(kind in listOf("csqtt","qwdtt","wdtt","csqtt25","openflux")) {
            for(id in listOf("","legacy","id|other","1-2\n"))assertFalse(CarrierChallenge.scopedExpirySupported(kind,id))
        }
        assertFalse(CarrierChallenge.scopedExpirySupported("csqtt25","a".repeat(48)))
        assertFalse(CarrierChallenge.scopedExpirySupported("csqtt","a".repeat(32)))
        assertFalse(CarrierChallenge.scopedExpirySupported("qwdtt","2-19"))
    }
}
