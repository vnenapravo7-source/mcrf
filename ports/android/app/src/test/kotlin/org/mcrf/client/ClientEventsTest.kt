package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ClientEventsTest {
    @Test fun masksEncryptedAndWireGuardLinksBeforePersistentLogging() {
        for(scheme in listOf("mcrf","happ","wireguard","wg","awg","v2raytun")) {
            val line=ClientEvents.redact("Import failed $scheme://encrypted/fixture-secret-payload next")
            assertFalse(line.contains("fixture-secret-payload"));assertTrue(line.contains("next"))
        }
    }
    @Test fun usefulContextRemainsWithoutAuthorizationOrCredentials() {
        val line=ClientEvents.redact("timeout Authorization: Bearer fixture-token password=fixture-password")
        assertTrue(line.contains("timeout"));assertFalse(line.contains("fixture-token"));assertFalse(line.contains("fixture-password"))
    }
}
