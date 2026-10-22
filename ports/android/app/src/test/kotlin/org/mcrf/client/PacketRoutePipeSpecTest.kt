package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Private DTO only, no actual fd, native stack or server. */
class PacketRoutePipeSpecTest {
    @Test fun assignedHostBitsSurviveRoundtripAndNonceIsPrivate() {
        val original=PacketRoutePipeSpec.create("mcrf-route-test",1420,listOf("10.89.0.2/24","fd89::2/64"))
        val parsed=PacketRoutePipeSpec.parse(original.json().toString())
        assertEquals(original.json().toString(),parsed.json().toString())
        assertEquals("10.89.0.2/24",parsed.addresses[0]);assertEquals("fd89::2/64",parsed.addresses[1])
        assertFalse(original.toString().contains(original.nonce))
    }
    @Test fun noHostnameUnownedAddressDuplicateOrDescriptorInDto() {
        for(addresses in listOf(listOf("owned.test/24"),listOf("0.0.0.0/32"),listOf("::/128"),listOf("ff02::1/128"),listOf("10.0.0.2/24","10.0.0.2/32")))
            assertThrows(IllegalArgumentException::class.java){PacketRoutePipeSpec.create("owned",1280,addresses)}
        val spec=PacketRoutePipeSpec.create("owned",1280,listOf("10.0.0.2/32")).json()
        assertThrows(IllegalArgumentException::class.java){PacketRoutePipeSpec.parse(JSONObject(spec.toString()).put("fd",42).toString())}
        assertThrows(IllegalArgumentException::class.java){PacketRoutePipeSpec.parse(JSONObject(spec.toString()).put("mtu","1280").toString())}
    }
}
