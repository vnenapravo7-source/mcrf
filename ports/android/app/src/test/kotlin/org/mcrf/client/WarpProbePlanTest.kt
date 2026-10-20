package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WarpProbePlanTest {
    @Test fun isolatedConfigHasAuthenticatedListenerAndOnlyCandidateFinal() {
        val account=JSONObject().put("id","fixture").put("token","fixture-only")
            .put("private_key",Base64.getEncoder().encodeToString(ByteArray(32) {(it+1).toByte()})).put("peer_public_key",WarpProfile.PEER_KEY).put("ipv4","172.16.0.2").toString()
        val entry=WarpProfile.initial(account)
        val config=WarpProbePlan.configuration(entry.original,31808,"fixture-user","fixture-password-only")
        val inputs=config.getJSONArray("inbounds");assertEquals(1,inputs.length())
        val inbound=inputs.getJSONObject(0);assertEquals("mixed",inbound.getString("type"));assertEquals("127.0.0.1",inbound.getString("listen"))
        assertEquals("fixture-user",inbound.getJSONArray("users").getJSONObject(0).getString("username"))
        assertEquals(1,config.getJSONArray("endpoints").length())
        assertEquals(config.getJSONArray("endpoints").getJSONObject(0).getString("tag"),config.getJSONObject("route").getString("final"))
        assertEquals(entry.endpoint!!.getString("private_key"),config.getJSONArray("endpoints").getJSONObject(0).getString("private_key"))
        val request=WarpProbePlan.request(entry.original,31808,"fixture-user","fixture-password-only")
        assertEquals(WarpProbePlan.OPERATION,request.getString("operation"))
        assertEquals(request.getJSONObject("config").getJSONObject("route").getString("final"),request.getJSONArray("servers").getJSONObject(0).getString("tag"))
        assertEquals(31808,request.getInt("tracePort"))
        assertThrows(Exception::class.java) {WarpProbePlan.configuration(entry.original,0,"user","fixture-password-only")}
    }
}
