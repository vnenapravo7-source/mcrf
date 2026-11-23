package org.mcrf.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedAppFreezePolicyTest {
    @Test fun ownedAndExternalVpnApplyDifferentFreezeRules() {
        val watched = setOf("hidden")
        val vpnOnly = setOf("vpn-only")

        assertEquals(setOf("hidden"), ManagedAppFreezePolicy.automaticTargets(
            watched, vpnOnly, ownsTunnel = true, externalVpn = false
        ))
        assertEquals(setOf("hidden", "vpn-only"), ManagedAppFreezePolicy.automaticTargets(
            watched, vpnOnly, ownsTunnel = false, externalVpn = true
        ))
        assertEquals(setOf("vpn-only"), ManagedAppFreezePolicy.automaticTargets(
            watched, vpnOnly, ownsTunnel = false, externalVpn = false
        ))
    }
}
