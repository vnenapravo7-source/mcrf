package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeRouteTunDnsTest {
    private fun source(addresses:String="[\"10.89.0.2/32\"]")=JSONObject("""{"inbounds":[{"type":"tun","tag":"tun-in","address":$addresses}],"dns":{"final":"owned-doh"},"route":{"final":"owned-output"}}""")
    @Test fun steerHostOnlyPrefixGetsExplicitPrivateDnsWithoutMutation(){
        val original=source();val before=original.toString();val result=NativeRouteTunDns.apply(original)
        val tun=result.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("10.89.0.1",tun.getJSONArray("dns_address").getString(0))
        assertEquals("10.89.0.2/32",tun.getJSONArray("address").getString(0));assertEquals(before,original.toString())
        assertEquals(original.getJSONObject("dns").toString(),result.getJSONObject("dns").toString())
        assertEquals(original.getJSONObject("route").toString(),result.getJSONObject("route").toString())
    }
    @Test fun explicitDnsAndUnrelatedTunRemainUntouched(){
        val original=source();original.getJSONArray("inbounds").getJSONObject(0).put("dns_address",org.json.JSONArray().put("192.0.2.53"))
        assertEquals(original.toString(),NativeRouteTunDns.apply(original).toString())
        val ordinary=source("[\"172.19.0.1/30\"]")
        assertEquals(ordinary.toString(),NativeRouteTunDns.apply(ordinary).toString())
    }
    @Test fun dualStackAndAppScopeAreRetained(){
        val original=source("[\"10.89.0.2/32\",\"fdfe:dcba:9876::1/126\"]")
        original.getJSONArray("inbounds").getJSONObject(0).put("exclude_package",org.json.JSONArray().put("org.mcrf.ownedprotocols"))
        val result=NativeRouteTunDns.apply(original);val expected=JSONObject(original.toString())
        expected.getJSONArray("inbounds").getJSONObject(0).put("dns_address",org.json.JSONArray().put("10.89.0.1"))
        assertEquals(expected.toString(),result.toString())
    }
}
