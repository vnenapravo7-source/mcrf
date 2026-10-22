package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.mcrf.engine.protocol.pipe.v1.PacketPipeBackend

/** DTO/negotiation only, NOT packet channel, WG or Android acceptance. */
class PacketPipeAdmissionTest {
    @Test fun missingLegacyCapabilityRemainsOffAndUnknownDeclarationsRefuse() {
        assertFalse(PacketPipeAdmission.admitted(null))
        assertTrue(PacketPipeAdmission.admitted(PacketPipeBackend.CAPABILITY))
        for(value in listOf<Any>(true,1,"", "owned_unix_dgram_v2"))
            assertThrows(IllegalArgumentException::class.java){PacketPipeAdmission.admitted(value)}
    }
    @Test fun pipeRequestIsSeparateRedactedAndRequiresExactOwnerAndMtu() {
        val request=PacketPipeBackend.Request(7,42,1280,100,"fixture-private-uapi")
        assertEquals(42,request.borrowedPrivateFd);assertEquals(100,request.peerPid)
        assertFalse(request.toString().contains(request.secretConfiguration()))
        for(mtu in listOf(0,1279,9001))
            assertThrows(IllegalArgumentException::class.java){PacketPipeBackend.Request(7,42,mtu,100,"owned")}
        assertThrows(IllegalArgumentException::class.java){PacketPipeBackend.Request(7,42,1280,0,"owned")}
        assertThrows(IllegalArgumentException::class.java){PacketPipeBackend.Request(0,42,1280,100,"owned")}
        assertThrows(IllegalArgumentException::class.java){PacketPipeBackend.Request(7,-1,1280,100,"owned")}
    }
}
