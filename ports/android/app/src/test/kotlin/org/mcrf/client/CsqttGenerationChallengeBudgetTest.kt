package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CsqttGenerationChallengeBudgetTest {
    @Test fun pinnedRustGenerationsRetainTheirActualNativeBudgets() {
        val nativeId="a".repeat(48)
        assertEquals(9000L,CarrierChallenge.nativeTimeoutMillis("csqtt","auto",nativeId))
        for(module in listOf("csqtt","csqtt20","csqtt215")) {
            assertEquals(9000L,CarrierChallenge.nativeTimeoutMillis(module,"auto",nativeId))
            assertEquals(59000L,CarrierChallenge.nativeTimeoutMillis(module,"manual",nativeId))
            assertEquals(119000L,CarrierChallenge.nativeTimeoutMillis(module,"selected",nativeId))
            assertTrue(CarrierChallenge.scopedExpirySupported(module,nativeId))
        }
        assertEquals(150000L,CarrierChallenge.nativeTimeoutMillis("csqtt25","auto",nativeId))
        assertEquals(150000L,CarrierChallenge.nativeTimeoutMillis("csqtt25","manual",nativeId))
        assertFalse(CarrierChallenge.scopedExpirySupported("csqtt25",nativeId))
    }
    @Test fun taggedNativeCancellationRemainsGenerationScoped() {
        assertTrue(CarrierChallenge.scopedExpirySupported("csqtt","a".repeat(48)))
        assertTrue(CarrierChallenge.scopedExpirySupported("csqtt20","a".repeat(48)))
        assertTrue(CarrierChallenge.scopedExpirySupported("csqtt215","a".repeat(48)))
        assertFalse(CarrierChallenge.scopedExpirySupported("csqtt20","1-2"))
        assertFalse(CarrierChallenge.scopedExpirySupported("csqtt215","a".repeat(32)))
        assertTrue(CarrierChallenge.scopedExpirySupported("qwdtt","b".repeat(32)))
        assertTrue(CarrierChallenge.scopedExpirySupported("wdtt","1-2"))
        assertFalse(CarrierChallenge.scopedExpirySupported("csqtt25","1-2"))
        assertEquals(24000L,CarrierChallenge.nativeTimeoutMillis("wdtt","auto","1-2"))
    }
}
