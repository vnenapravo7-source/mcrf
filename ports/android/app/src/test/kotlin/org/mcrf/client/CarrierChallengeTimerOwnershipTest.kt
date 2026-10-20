package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class CarrierChallengeTimerOwnershipTest {
    @Test fun timerAttachedAfterRetirementIsImmediatelyCancelled() {
        withTimer {request,timer->
            request.cancelExpiry()
            request.attachExpiry(timer)
            assertTrue(timer.isCancelled)
        }
    }
    @Test fun timerAttachedBeforeRetirementIsCancelled() {
        withTimer {request,timer->
            request.attachExpiry(timer)
            request.cancelExpiry()
            request.cancelExpiry()
            assertTrue(timer.isCancelled)
        }
    }
    private fun withTimer(check:(CarrierChallenge.Request,java.util.concurrent.ScheduledFuture<*>)->Unit) {
        val executor=ScheduledThreadPoolExecutor(1).apply {setRemoveOnCancelPolicy(true)}
        try {
            val request=CarrierChallenge.Request("fixture","https://vk.com/captcha",{})
            val timer=executor.schedule({fail("fixture timer must not fire")},1,TimeUnit.DAYS)
            check(request,timer)
            assertTrue(executor.queue.isEmpty())
        }finally {executor.shutdownNow()}
    }
}
