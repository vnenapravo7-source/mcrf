package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SteerRouteTuningTest {
    @Test fun transportDefaultsCannotBeAppliedOnlyToTheDispatcher(){
        for(value in listOf(CoreTuningSettings(certificateStore="mozilla"),CoreTuningSettings(ntpEnabled=true),
            CoreTuningSettings(insecure=true),CoreTuningSettings(muxDefault=true),CoreTuningSettings(fragmentDefault=true),
            CoreTuningSettings(fingerprint="chrome"),CoreTuningSettings(initialPacketSize=1200),CoreTuningSettings(maxStreams=10))){
            assertThrows(IllegalArgumentException::class.java){SteerRouteTuning.requireSupported(value)}
        }
    }
    @Test fun disabledCompanionParametersAreNotResetOrEmitted(){
        val value=CoreTuningSettings(ntpServer="owned.example.test",muxConcurrency=32,muxPadding=true,fragmentDelay="750ms")
        val before=value.json().toString();SteerRouteTuning.requireSupported(value);assertEquals(before,value.json().toString())
    }
    @Test fun dispatcherOwnedAdminApiIsNotAProtocolTlsSetting(){
        SteerRouteTuning.requireSupported(CoreTuningSettings(apiEnabled=true,apiSecret="owned-fixture-secret"))
    }
}
