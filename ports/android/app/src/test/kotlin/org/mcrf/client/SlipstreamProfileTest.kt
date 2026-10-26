package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SlipstreamProfileTest {
    @Test fun moduleRequiresExactSourceAndPrivateIngressContract() {
        assertTrue(SlipstreamProfile.compatible("slipstream",SlipstreamProfile.REPOSITORY,SlipstreamProfile.COMMIT,1))
        assertFalse(SlipstreamProfile.compatible("slipstream","EndPositive/slipstream",SlipstreamProfile.COMMIT,1))
        assertFalse(SlipstreamProfile.compatible("dnstt",SlipstreamProfile.REPOSITORY,SlipstreamProfile.COMMIT,1))
        assertFalse(SlipstreamProfile.compatible("slipstream",SlipstreamProfile.REPOSITORY,"0".repeat(40),1))
        assertFalse(SlipstreamProfile.compatible("slipstream",SlipstreamProfile.REPOSITORY,SlipstreamProfile.COMMIT,0))
        assertEquals(setOf("libslipstream.so","libslipstream_core.so"),EngineModules.libraries("slipstream"))
        assertFalse(EngineModules.validNativeEntries("slipstream","arm64-v8a",listOf("lib/arm64-v8a/libslipstream.so")))
    }
    @Test fun unsafeImportDoesNotFallbackToAnotherDnsCarrier() {
        val value=JSONObject().put("type","slipstream").put("domain","t.example.test").put("resolver","127.0.0.1:53").put("certificate","")
        for(key in listOf("insecure","socks","backend","authoritative","probe_only")) {
            try {SlipstreamProfile.fromJson("fixture",JSONObject(value.toString()).put(key,true));fail("unsafe key accepted")}
            catch(_:IllegalArgumentException){}
        }
        try {SlipstreamProfile.fromJson("fixture",value);fail("missing certificate accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun errorsPreserveCauseWithoutShowingArbitraryBackendOutput() {
        assertTrue(requireNotNull(DnsCarrierFailure.describe("slipstream","MCRF_ERROR CERTIFICATE_MISMATCH")).contains("не совпадает"))
        assertNotNull(DnsCarrierFailure.describe("slipstream","MCRF_ERROR BACKEND_EXIT_1"))
        assertNull(DnsCarrierFailure.describe("slipstream","MCRF_ERROR BACKEND_EXIT_1 secret"))
        assertNull(DnsCarrierFailure.describe("slipstream","MCRF_ERROR password=secret"))
        assertNull(DnsCarrierFailure.describe("dnstt","MCRF_ERROR CERTIFICATE_MISMATCH"))
    }
}
