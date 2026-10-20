package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierQwdttWaiterTest {
    private val first="0123456789abcdef0123456789abcdef"
    private val second="1123456789abcdef0123456789abcdef"
    @Test fun nativeBudgetsAreShortenedOnlyForIdentifiedNewQwdttContract() {
        assertEquals(9000L,CarrierChallenge.nativeTimeoutMillis("qwdtt","auto",first))
        assertEquals(59000L,CarrierChallenge.nativeTimeoutMillis("qwdtt","manual",first))
        assertEquals(119000L,CarrierChallenge.nativeTimeoutMillis("qwdtt","selected",first))
        assertEquals(150000L,CarrierChallenge.nativeTimeoutMillis("qwdtt","auto"))
        assertEquals(150000L,CarrierChallenge.nativeTimeoutMillis("qwdtt","auto","older-contract"))
    }
    @Test fun identicalCaptchaAtNewWaiterCanProceedWithoutRelaxingRetiredReplayGuard() {
        val flights=CarrierChallengeFlights()
        fun envelope(id:String)=requireNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|$id|auto|https://vk.com/captcha|fixture-session",true))
        val a=envelope(first);val b=envelope(second)
        val ticket=requireNotNull(flights.claim(a.flightKey));flights.retire(ticket)
        assertNull(flights.claim(a.flightKey))
        assertNotNull(flights.claim(b.flightKey))
        assertEquals("CAPTCHA_RESULT|$second|fixture-proof\n",CarrierChallenge.command(b.nativeId,"fixture-proof"))
    }
    @Test fun automaticCaptchaDoesNotConsumeTheHumanWaitingAllowance() {
        val auto=requireNotNull(CarrierChallenge.registerBounded(first,"https://vk.com/captcha",{},automatic=true))
        val manual=requireNotNull(CarrierChallenge.registerBounded(second,"https://vk.com/captcha",{},automatic=false))
        try {
            assertFalse(CarrierChallenge.isManualPending(auto))
            assertTrue(CarrierChallenge.isManualPending(manual))
            assertFalse(CarrierChallenge.isManualPending("missing"))
            val budget=CarrierStartupBudget(1000,0)
            assertTrue(budget.canContinue(0,CarrierChallenge.isManualPending(auto)))
            assertFalse(budget.canContinue(1001,CarrierChallenge.isManualPending(auto)))
            CarrierChallenge.remove(manual)
            assertFalse(CarrierChallenge.isManualPending(manual))
        }finally{CarrierChallenge.remove(auto);CarrierChallenge.remove(manual)}
    }
}
