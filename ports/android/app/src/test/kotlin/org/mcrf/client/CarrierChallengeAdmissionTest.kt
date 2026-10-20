package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierChallengeAdmissionTest {
    @Test fun strictNativeSwgAdmitsAllThreeAuditedModesInBothIpcShapes(){
        for(mode in listOf("auto","manual","selected")){
            assertNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|$mode|https://vk.com/captcha|owned-session",true))
            assertNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|owned|$mode|https://vk.com/captcha|owned-session",true))
        }
    }
    @Test fun strictNativeSwgRejectsUnknownOrControlMode(){
        for(mode in listOf("","wv","auto\t","unknown")){
            assertNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|$mode|https://vk.com/captcha|owned-session",true))
        }
    }
    @Test fun strictNativeSwgRejectsEmptyOrControlSessionWithoutRetainingIt(){
        for(session in listOf(""," ","owned\tsecret","owned\rsecret","owned\u0085secret")){
            assertNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|manual|https://vk.com/captcha|$session",true))
        }
    }
    @Test fun otherProvidersKeepTheirLegacyAdmissionPath(){
        assertNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|legacy-mode|https://vk.com/captcha|"))
    }
    @Test fun nativeModeIsRetainedWithoutRetainingSessionProof(){
        val old=requireNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|auto|https://vk.com/captcha|fixture-secret"))
        val current=requireNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|fixture-id|manual|https://vk.com/captcha|fixture-secret"))
        assertEquals("auto",old.mode);assertEquals("",old.nativeId)
        assertEquals("manual",current.mode);assertEquals("fixture-id",current.nativeId)
        assertFalse(old.toString().contains("fixture-secret"));assertFalse(old.toString().contains("vk.com"))
        assertEquals(24000L,CarrierChallenge.nativeTimeoutMillis("wdtt",old.mode))
        assertEquals(9000L,CarrierChallenge.nativeTimeoutMillis("csqtt",old.mode))
        assertEquals(59000L,CarrierChallenge.nativeTimeoutMillis("csqtt","manual"))
        assertEquals(119000L,CarrierChallenge.nativeTimeoutMillis("csqtt","selected"))
        assertEquals(150000L,CarrierChallenge.nativeTimeoutMillis("wdtt",current.mode))
    }
    @Test fun automaticRequestExpiresBeforeNativeDeadlineAndRejectsLateProof(){
        var replies=0;var retired=0
        val id=requireNotNull(CarrierChallenge.registerBounded("fixture","https://vk.com/captcha",{replies++},{retired++},24000))
        try {
            val request=requireNotNull(CarrierChallenge.get(id))
            assertEquals(24000L,request.remainingMillis(request.created))
            assertEquals(0L,request.remainingMillis(request.created+24_000_000_001L))
            CarrierChallenge.expire(request.created+24_000_000_001L)
            CarrierChallenge.respond(id,"late-fixture-proof")
            assertNull(CarrierChallenge.get(id));assertEquals(0,replies);assertEquals(1,retired)
        }finally{CarrierChallenge.remove(id)}
    }
    @Test fun olderUnopenedChallengeIsPresentedBeforeLaterRequests(){
        val first=requireNotNull(CarrierChallenge.registerBounded("first","https://vk.com/captcha",{}))
        val second=requireNotNull(CarrierChallenge.registerBounded("second","https://vk.com/captcha",{}))
        try {
            assertEquals(first,CarrierChallenge.unopened()?.id)
            requireNotNull(CarrierChallenge.get(first)).opened=true
            assertEquals(second,CarrierChallenge.unopened()?.id)
        }finally{CarrierChallenge.remove(first);CarrierChallenge.remove(second)}
    }
    @Test fun excessChallengeCannotGrowQueueAndRetirementFreesCapacity(){
        val ids=mutableListOf<String>()
        var replied=0
        try {
            repeat(32){ids+=requireNotNull(CarrierChallenge.registerBounded("fixture-$it","https://vk.com/captcha",{replied++}))}
            assertNull(CarrierChallenge.registerBounded("overflow","https://vk.com/captcha",{replied++}))
            assertEquals(0,replied)
            CarrierChallenge.remove(ids.removeAt(0))
            ids+=requireNotNull(CarrierChallenge.registerBounded("replacement","https://vk.com/captcha",{replied++}))
            assertEquals(0,replied)
        }finally{ids.forEach(CarrierChallenge::remove)}
    }
    @Test fun boundedAdmissionRetainsStrictOriginAndExactlyOnceReply(){
        assertThrows(IllegalArgumentException::class.java){CarrierChallenge.registerBounded("fixture","https://vk.com.evil.test/captcha",{})}
        var replies=0;var retired=0
        val id=requireNotNull(CarrierChallenge.registerBounded("fixture","https://vk.com/captcha",{replies++},{retired++}))
        try {
            CarrierChallenge.respond(id,"fixture-proof")
            CarrierChallenge.respond(id,"fixture-proof")
            assertEquals(1,replies);assertEquals(1,retired)
        }finally{CarrierChallenge.remove(id)}
    }
}
