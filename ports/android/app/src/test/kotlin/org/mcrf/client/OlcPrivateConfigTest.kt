package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OlcPrivateConfigTest {
    private fun profile()=JSONObject().put("type","olcrtc").put("provider","jitsi").put("transport","vp8channel")
        .put("room","https://meet.example.test/owned").put("key","ab".repeat(32)).put("options",JSONObject().put("vp8-fps",45))
    @Test fun nativeConfigKeepsOnlyRuntimeProtocolFields(){
        val config=OlcPrivateConfig.create(profile().put("name","private display").put("probe_only",true).put("outbound_tag","private-route"),12345,"local","private","owned-id")
        assertEquals(setOf("provider","transport","room","key","dns","options","socks","socks_user","socks_pass","device_id"),config.keys().asSequence().toSet())
        assertEquals(45,config.getJSONObject("options").getInt("vp8-fps"));assertEquals("owned-id",config.getString("device_id"))
    }
    @Test fun diagnosticsNeverExposeRoomOrSymmetricKey(){
        val source=profile();val detail=CarrierFailureDetail("olcrtc",source)
        detail.offer("error: ${source.getString("room")} ${source.getString("key")}")
        val result=detail.describe("запуск",1)
        assertFalse(result.contains(source.getString("room")));assertFalse(result.contains(source.getString("key")))
        assertNotNull(DnsCarrierFailure.describe("olcrtc","MCRF_ERROR OLC_SESSION_FAILED"))
        assertTrue(DnsCarrierFailure.describe("olcrtc","MCRF_ERROR OLC_HTTP_403")!!.contains("HTTP 403"))
        assertNull(DnsCarrierFailure.describe("olcrtc","MCRF_ERROR OLC_HTTP_403 private-token"))
        assertNull(DnsCarrierFailure.describe("olcrtc","MCRF_ERROR OLC_SESSION_FAILED private-token"))
    }
}
