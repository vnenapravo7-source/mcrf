package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SteerCompatibilityHintsTest {
    @Test fun dnsAndRulesExplainWhatStillWorks() {
        assertTrue(SteerCompatibilityHints.describe("ADVANCED_DNS","").contains("UDP, DoH и DoT"))
        assertTrue(SteerCompatibilityHints.describe("RULE_DNS","").contains("общий DNS"))
        assertTrue(SteerCompatibilityHints.describe("GEO_SELECTORS","").contains("доменные и IPv4-правила"))
    }
    @Test fun tunIssuesHaveSpecificActionsInsteadOfSchemaNames() {
        assertTrue(SteerCompatibilityHints.describe("TUN_UNSUPPORTED","tun.ipv6").contains("Отключи IPv6"))
        assertTrue(SteerCompatibilityHints.describe("TUN_UNSUPPORTED","tun.shareLan").contains("раздачу VPN"))
        assertTrue(SteerCompatibilityHints.describe("TUN_MTU","tun.mtu").contains("1500"))
    }
    @Test fun untrustedFieldAndCodeAreNotEchoedToTheUi() {
        val secret="private_key=do-not-display"
        for(code in listOf(secret,"CORE_TUNING","TUN_UNSUPPORTED","ADDITIONAL_FEATURE")) {
            val message=SteerCompatibilityHints.describe(code,secret)
            assertFalse(message.contains(secret));assertTrue(message.isNotBlank());assertTrue(message.length<400)
        }
    }
}
