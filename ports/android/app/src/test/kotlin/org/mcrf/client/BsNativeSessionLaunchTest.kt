package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class BsNativeSessionLaunchTest {
    private val context="""{"kind":"wifi","expensive":false,"constrained":false,"ssid":"owned","bssid":""}""".toByteArray()
    @Test fun network_message_matches_native_bootstrap_contract() {
        val message=String(BsNativeSessionLaunch.command(42,200,true,false,context),Charsets.US_ASCII)
        assertEquals("BS_NATIVE_NETWORK|42|200|1|0|${Base64.getEncoder().encodeToString(context)}\n",message)
        assertEquals(1,message.count {it=='\n'})
    }
    @Test fun both_address_families_are_retained() {
        assertTrue(String(BsNativeSessionLaunch.command(42,200,true,true,context)).startsWith("BS_NATIVE_NETWORK|42|200|1|1|"))
        assertTrue(String(BsNativeSessionLaunch.command(42,200,false,true,context)).startsWith("BS_NATIVE_NETWORK|42|200|0|1|"))
    }
    @Test fun invalid_or_oversized_network_context_never_reaches_child() {
        for(action in listOf<()->Unit>(
            {BsNativeSessionLaunch.command(0,200,true,false,context)},
            {BsNativeSessionLaunch.command(42,0,true,false,context)},
            {BsNativeSessionLaunch.command(42,200,false,false,context)},
            {BsNativeSessionLaunch.command(42,200,true,false,ByteArray(2049))}
        )) assertThrows(IllegalArgumentException::class.java,action)
    }
}
