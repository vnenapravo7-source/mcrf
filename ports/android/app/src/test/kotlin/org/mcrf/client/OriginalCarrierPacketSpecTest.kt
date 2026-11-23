package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Admission only; no module/JNI/FD/runtime acceptance claim. */
class OriginalCarrierPacketSpecTest {
    private fun valid()=OriginalCarrierPacketSpec("owned-carrier","a".repeat(32),"10.89.0.2",1300)
    @Test fun retainsHostAndEndpointIdentityWithoutExposingNonceInDiagnostics() {
        val source=valid();val parsed=OriginalCarrierPacketSpec.parse(source.json())
        assertEquals(source.json(),parsed.json());assertEquals("10.89.0.2",parsed.local)
        assertFalse(parsed.toString().contains(source.nonce))
        assertNotEquals(PacketRoutePipeSpec.CAPABILITY,OriginalCarrierPacketSpec.CAPABILITY)
    }
    @Test fun rejectsAmbiguousOrNonUnicastAddressWithoutDnsResolution() {
        for(local in listOf("owned.test","127.0.0.1","0.1.2.3","224.0.0.1","255.255.255.255","10.01.0.2","10.0.0.256","::1","10.0.0.2/24"))
            assertThrows(IllegalArgumentException::class.java){OriginalCarrierPacketSpec("owned","a".repeat(32),local,1300)}
    }
    @Test fun strictTypesFieldsAndNativeMtuBounds() {
        for(mutation in listOf("fd" to 42,"mtu" to "1300","schema" to 2,"mtu" to 575,"mtu" to 1985,"nonce" to "bad","tag" to "bad/tag"))
            assertThrows(IllegalArgumentException::class.java){OriginalCarrierPacketSpec.parse(JSONObject(valid().json()).put(mutation.first,mutation.second).toString())}
        for(mtu in listOf(576,1984))assertEquals(mtu,OriginalCarrierPacketSpec("owned","b".repeat(32),"10.0.0.2",mtu).mtu)
    }
}
