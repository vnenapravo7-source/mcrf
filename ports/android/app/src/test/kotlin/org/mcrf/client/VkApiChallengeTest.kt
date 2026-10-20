package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VkApiChallengeTest {
    @Test fun continuationIsExactlyOnceAndDoesNotReplayOtherResponses(){
        var solved=0;var sent=0
        val repeated=rejected()
        assertSame(repeated,VkApiChallenge.continueOnce(rejected(),{solved++;"fixture-proof"}){sent++;repeated})
        assertEquals(1,solved);assertEquals(1,sent)
        val success=JSONObject().put("response",JSONObject())
        assertSame(success,VkApiChallenge.continueOnce(success,{error("must not solve")}){error("must not send")})
        val rateLimit=JSONObject().put("error",JSONObject().put("error_code",6))
        assertSame(rateLimit,VkApiChallenge.continueOnce(rateLimit,{error("must not solve")}){error("must not send")})
    }
    private fun rejected(url:String="https://id.vk.ru/captcha")=JSONObject().put("error",JSONObject()
        .put("error_code",14).put("captcha_sid","fixture-sid").put("redirect_uri",url).put("captcha_ts","123"))
    @Test fun explicitRejectionKeepsOnlyContinuationFields(){
        val challenge=requireNotNull(VkApiChallenge.parse(rejected()))
        assertEquals(mapOf("captcha_sid" to "fixture-sid","captcha_ts" to "123","captcha_key" to "","success_token" to "fixture-proof"),challenge.continuation("fixture-proof"))
        assertFalse(challenge.toString().contains("fixture-sid"))
    }
    @Test fun ambiguousSuccessUntrustedUrlsAndInvalidProofAreRefused(){
        assertNull(VkApiChallenge.parse(rejected().put("response",JSONObject())))
        for(url in listOf("http://id.vk.ru/captcha","https://id.vk.ru.evil.invalid/captcha","https://user@id.vk.ru/captcha","https://id.vk.ru:444/captcha","https://id.vk.ru/login"))assertNull(VkApiChallenge.parse(rejected(url)))
        val challenge=requireNotNull(VkApiChallenge.parse(rejected()))
        try{challenge.continuation("bad\nproof");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun ordinaryHttpsPortAndExactVkOriginsWork(){
        for(host in listOf("oauth.vk.ru","login.vk.com","www.vk.ru","api.vk.com"))assertTrue(CarrierChallenge.allowed("https://$host:443/captcha"))
        assertFalse(CarrierChallenge.allowed("https://untrusted.vk.ru/captcha"))
    }
}
