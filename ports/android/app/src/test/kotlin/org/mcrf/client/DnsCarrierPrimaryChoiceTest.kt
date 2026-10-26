package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class DnsCarrierPrimaryChoiceTest {
    @Test fun dnsAndRtcCarriersOfferExplicitDispatcherChoiceInsteadOfLateGenericFailure() {
        for(kind in listOf("olcrtc","dnstt","vaydns","slipstream")) {
            assertTrue(CarrierPrimaryChoice.required("steer",kind))
            assertFalse(CarrierPrimaryChoice.required("singbox",kind))
            assertFalse(CarrierPrimaryChoice.required(null,kind))
            val explanation=CarrierPrimaryChoice.explanation(kind)
            assertTrue(explanation.contains("явно выбери sing-box"))
            assertTrue(explanation.contains("без твоего подтверждения"))
        }
    }
    @Test fun unrelatedAndWireGuardSelectionsDoNotPromptForDnsDispatcher() {
        for(kind in listOf("wireguard","awg","unknown"))assertFalse(CarrierPrimaryChoice.required("steer",kind))
    }
}
