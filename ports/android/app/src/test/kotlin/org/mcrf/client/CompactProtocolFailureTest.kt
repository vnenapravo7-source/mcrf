package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CompactProtocolFailureTest {
    @Test fun typedNativeCausesAreVisibleWithoutTrustFallback(){
        val detail=CompactProtocolFailure.describe("protocols","MCRF_ERROR PROTOCOL_CRONET_202")!!
        assertTrue(detail.contains("PROTOCOL_CRONET_202"));assertTrue(detail.contains("сертификата"))
        assertTrue(CompactProtocolFailure.trustFailure("MCRF_ERROR PROTOCOL_CRONET_202"))
        assertFalse(CompactProtocolFailure.trustFailure("MCRF_ERROR PROTOCOL_CRONET_102"))
        assertTrue(CompactProtocolFailure.describe("protocols","MCRF_ERROR PROTOCOL_FAILURE_UNCLASSIFIED")!!.contains("не установлен"))
    }
    @Test fun unknownNativePayloadDoesNotBecomeDiagnostic(){
        assertNull(CompactProtocolFailure.describe("protocols","MCRF_ERROR PROTOCOL_CRONET_202 token=private"))
        assertNull(CompactProtocolFailure.describe("tls-core","MCRF_ERROR PROTOCOL_CRONET_202"))
        assertNull(CompactProtocolFailure.describe("protocols","MCRF_ERROR PROTOCOL_CRONET_10001"))
    }
}
