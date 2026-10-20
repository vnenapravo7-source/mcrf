package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierStartupFailureTest {
    @Test fun probeSignalMustBeExactAndCannotSmuggleNativeOutput(){
        assertTrue(CarrierStartupFailure.isVerificationSignal("MCRF_CAPTCHA_REQUIRED"))
        for(line in listOf(" MCRF_CAPTCHA_REQUIRED","MCRF_CAPTCHA_REQUIRED|https://fixture.invalid/token","prefix MCRF_CAPTCHA_REQUIRED","MCRF_CAPTCHA_REQUIRED\n","CAPTCHA_SOLVE|auto|url|secret")) {
            assertFalse(CarrierStartupFailure.isVerificationSignal(line))
        }
        assertTrue(CarrierStartupFailure.verificationRequired.contains("вручную"))
        assertFalse(CarrierStartupFailure.verificationRequired.contains("://"))
    }
    @Test fun missingReadyDoesNotInventServerFailure(){
        val message=CarrierStartupFailure.timeout(false,false)
        assertTrue(message.contains("не сообщил о готовности транспорта"))
        assertTrue(message.contains("Причина не установлена"))
        assertFalse(message.contains("VK"))
    }
    @Test fun readyTransportSeparatesLocalProxyFailure(){
        for(verification in listOf(false,true)){
            val message=CarrierStartupFailure.timeout(true,verification)
            assertTrue(message.contains("локальный прокси"))
            assertFalse(message.contains("VK"))
            assertTrue(message.contains("не установлено"))
        }
    }
    @Test fun verificationIsAnObservationNotAClaimOfWrongPassword(){
        val message=CarrierStartupFailure.timeout(false,true)
        assertTrue(message.contains("VK запрашивал проверку"))
        assertTrue(message.contains("не доказывает причину"))
        assertFalse(message.contains("парол"))
    }
    @Test fun allStatesHaveDistinctBoundedMessages(){
        val messages=setOf(CarrierStartupFailure.timeout(false,false),CarrierStartupFailure.timeout(false,true),CarrierStartupFailure.timeout(true,false))
        assertEquals(3,messages.size)
        assertTrue(messages.all {it.length<=300&&!it.contains("://")&&!it.contains('\n')})
    }
}
