package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierCaptchaAutoPageTest {
    @Test fun acceptsOnlyBoundedSuccessAndClosedFallbackCodes() {
        assertEquals("owned-proof",CarrierCaptchaAutoPage.result("MCRF_AUTO_CAPTCHA|ok|owned-proof"))
        assertEquals("error:slider_detected",CarrierCaptchaAutoPage.result("MCRF_AUTO_CAPTCHA|slider"))
        assertEquals("error:auto_rejected",CarrierCaptchaAutoPage.result("MCRF_AUTO_CAPTCHA|rejected"))
        for(value in listOf("provider text","MCRF_AUTO_CAPTCHA|unknown","MCRF_AUTO_CAPTCHA|ok|",
            "MCRF_AUTO_CAPTCHA|ok|error:cancelled","MCRF_AUTO_CAPTCHA|ok|ERROR:fake",
            "MCRF_AUTO_CAPTCHA|ok|a|b","MCRF_AUTO_CAPTCHA|ok|a\nb","MCRF_AUTO_CAPTCHA|ok|"+"a".repeat(16385)))assertNull(CarrierCaptchaAutoPage.result(value))
    }
    @Test fun automaticStageDoesNotAppearAsManualAndBrowserOwnershipIsShared() {
        val auto=requireNotNull(CarrierChallenge.registerBounded("owned-auto","https://vk.com/captcha",{},timeoutMillis=9000,automatic=true))
        val manual=requireNotNull(CarrierChallenge.registerBounded("owned-manual","https://vk.com/captcha",{}))
        try {
            assertEquals(manual,CarrierChallenge.unopened()?.id)
            requireNotNull(CarrierChallenge.get(manual)).opened=true
            assertNull(CarrierChallenge.unopened());assertEquals(manual,CarrierChallenge.pendingManual()?.id)
            assertTrue(CarrierChallenge.claimBrowser(auto));assertFalse(CarrierChallenge.claimBrowser(manual))
            CarrierChallenge.remove(auto)
            assertTrue(CarrierChallenge.claimBrowser(manual))
        }finally {CarrierChallenge.remove(auto);CarrierChallenge.remove(manual)}
    }
    @Test fun automaticExpiryNeverConsumesTheNextManualChallenge() {
        var replies=0
        val auto=requireNotNull(CarrierChallenge.registerBounded("owned-auto","https://vk.com/captcha",{replies++},timeoutMillis=9000,automatic=true))
        val manual=requireNotNull(CarrierChallenge.registerBounded("owned-manual","https://vk.com/captcha",{}))
        try {
            val request=requireNotNull(CarrierChallenge.get(auto))
            CarrierChallenge.expire(request.created+9_000_000_001L)
            CarrierChallenge.respond(auto,"late-proof")
            assertEquals(0,replies);assertEquals(manual,CarrierChallenge.unopened()?.id)
        }finally {CarrierChallenge.remove(auto);CarrierChallenge.remove(manual)}
    }
}
