package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DnsDetourTest {
    private fun config(outbound:JSONObject)=JSONObject().put("outbounds",JSONArray().put(outbound))
    private fun server()=JSONObject().put("type","udp").put("server","192.0.2.53")
    @Test fun bareDirectUsesNativePhysicalDialerInsteadOfInvalidEmptyDetour(){
        val outbound=JSONObject().put("type","direct").put("tag","direct")
        assertFalse(DnsConfig.applyDetour(server().put("detour","direct"),config(outbound),"direct").has("detour"))
    }
    @Test fun configuredDirectRetainsExplicitDialerPolicy(){
        val outbound=JSONObject().put("type","direct").put("tag","direct").put("bind_interface","fixture0")
        assertEquals("direct",DnsConfig.applyDetour(server(),config(outbound),"direct").getString("detour"))
        assertEquals("fixture0",outbound.getString("bind_interface"))
    }
    @Test fun proxyResolverNeverSilentlyBecomesDirect(){
        val outbound=JSONObject().put("type","socks").put("tag","proxy").put("server","192.0.2.1").put("server_port",1080)
        assertEquals("proxy",DnsConfig.applyDetour(server(),config(outbound),"proxy").getString("detour"))
    }
    @Test fun localResolverDoesNotAcquireUnsupportedDetour(){
        val outbound=JSONObject().put("type","direct").put("tag","direct")
        assertFalse(DnsConfig.applyDetour(JSONObject().put("type","local"),config(outbound),"direct").has("detour"))
    }
}
