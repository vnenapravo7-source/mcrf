package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CsqttDirectTunPolicyTest {
    @Test fun savedOnNeverSelectsAnOrdinaryVpn() {
        assertFalse(CsqttDirectTunPolicy.requested(true,true,false))
        assertFalse(CsqttDirectTunPolicy.requested(true,false,true))
        assertFalse(CsqttDirectTunPolicy.requested(false,true,true))
        assertTrue(CsqttDirectTunPolicy.requested(true,true,true))
    }
    @Test fun unsupportedBsIsNotSilentlySentThroughOrdinaryRouting() {
        assertTrue(CsqttDirectTunPolicy.requested(true,true,true)) // Start must reach explicit capability/profile rejection.
        assertNotNull(CsqttDirectTunSupport.profileReason(org.json.JSONObject().put("type","qwdtt"),true))
        assertNotNull(CsqttDirectTunSupport.profileReason(org.json.JSONObject().put("type","csqtt").put("csqtt_generation","2.1.5"),true))
    }
    @Test fun turningSavedOptionOffOnlyRestartsAnOwnedDirectSession() {
        assertFalse(CsqttDirectTunPolicy.reconfigure(false,false,false))
        assertFalse(CsqttDirectTunPolicy.reconfigure(false,false,true))
        assertTrue(CsqttDirectTunPolicy.reconfigure(false,true,false))
        assertTrue(CsqttDirectTunPolicy.reconfigure(true,false,true))
        assertFalse(CsqttDirectTunPolicy.reconfigure(true,false,false))
    }
    @Test fun admissionAndRetainedBlackholeRemainStoppable() {
        assertTrue(CsqttDirectTunPolicy.active(false,true)) // Admission/FD adoption: STOP, controls busy.
        assertTrue(CsqttDirectTunPolicy.active(true,false)) // Native loss: STOP, controls no longer busy.
        assertTrue(CsqttDirectTunPolicy.active(true,true))
        assertFalse(CsqttDirectTunPolicy.active(false,false)) // Completed STOP: no stale ownership.
    }
    @Test fun tunnelFrameIsCompleteAndNeverInventsDns() {
        val value=CsqttDirectTunSession.parseConfig("TUNCONF:10.4.0.2:1.1.1.1,8.8.8.8:0:v1:workers=40")
        assertEquals(2,value.dns.size);assertEquals(0,value.port);assertEquals("v1",value.revision);assertEquals(1500,value.mtu)
        for(text in listOf("TUNCONF:10.4.0.2:","TUNCONF:10.4.0.2:1.1.1.1","TUNCONF:10.4.0.2::0:v1",
            "TUNCONF:10.4.0.2:1.1.1.1:65536:v1","TUNCONF:10.4.0.2:1.1.1.1:0: ","TUNCONF:10.4.0.2:[::1]:0:v1"))
            assertTrue(runCatching {CsqttDirectTunSession.parseConfig(text)}.isFailure)
    }
}
