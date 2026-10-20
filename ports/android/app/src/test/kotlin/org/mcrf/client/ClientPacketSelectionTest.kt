package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ClientPacketSelectionTest {
    @Test fun literalIpv4IsNotSubstituted(){assertEquals("192.0.2.123",ClientPacketSelection.validationDial("192.0.2.123"))}
    @Test fun domainAdmissionUsesDocumentationAddressWithoutResolving(){assertEquals("192.0.2.1",ClientPacketSelection.validationDial("not-a-real-server.invalid"))}
    @Test fun unacceptedOuterIpv6IsRejectedBeforeSaving(){assertThrows(IllegalArgumentException::class.java){ClientPacketSelection.validationDial("2001:db8::1")}}
}
