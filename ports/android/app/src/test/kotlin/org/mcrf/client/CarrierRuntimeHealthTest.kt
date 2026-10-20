package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierRuntimeHealthTest {
    @Test fun aLivingChildDoesNotImplyAWebsiteFailureOrInternetSuccess() {
        for(kind in listOf("csqtt","wdtt","qwdtt","olcrtc","dnstt"))
            assertNull(CarrierRuntimeHealth.failure(kind,false,true,null))
    }
    @Test fun everyStoppedCarrierProducesFixedUsefulFailureWithoutRawNativeOutput() {
        for(kind in listOf("csqtt","wdtt","qwdtt","olcrtc","dnstt")) {
            val message=CarrierRuntimeHealth.failure(kind,false,false,null)!!
            assertTrue(message.contains("процесс транспорта завершился"))
            assertTrue(message.contains("логи"))
        }
        assertFalse(CarrierRuntimeHealth.failure("secret-kind",false,false,null)!!.contains("secret-kind"))
    }
    @Test fun retirementIsNeverReclassifiedAsANewConnectionFailure() {
        for(alive in listOf(false,true))for(fatal in listOf(null,CsqttNativeFailure.Kind.PROTOCOL))
            assertNull(CarrierRuntimeHealth.failure("csqtt",true,alive,fatal))
    }
    @Test fun typedCsqttRejectionSurvivesTheExitRaceButIsNotAppliedToOtherProtocols() {
        for(alive in listOf(false,true))assertEquals(CsqttNativeFailure.Kind.PROTOCOL.explanation,
            CarrierRuntimeHealth.failure("csqtt",false,alive,CsqttNativeFailure.Kind.PROTOCOL))
        assertNull(CarrierRuntimeHealth.failure("wdtt",false,true,CsqttNativeFailure.Kind.PROTOCOL))
    }
}
