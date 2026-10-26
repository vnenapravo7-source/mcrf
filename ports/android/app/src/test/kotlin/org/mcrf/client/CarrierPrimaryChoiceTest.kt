package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierPrimaryChoiceTest {
    @Test fun onlyKnownSwgTransportsWithExplicitSteerNeedTheChoice() {
        for(kind in listOf("wdtt","csqtt","qwdtt")){
            assertTrue(CarrierPrimaryChoice.required("steer",kind))
            assertFalse(CarrierPrimaryChoice.required("singbox",kind))
            assertFalse(CarrierPrimaryChoice.required(null,kind))
            assertTrue(CarrierPrimaryChoice.explanation(kind).contains("не изменятся без твоего подтверждения"))
        }
        for(kind in listOf("wireguard","awg","unknown"))assertFalse(CarrierPrimaryChoice.required("steer",kind))
        assertThrows(IllegalArgumentException::class.java){CarrierPrimaryChoice.explanation("untrusted-password")}
    }
}
