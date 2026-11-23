package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StandaloneRouterSelectionTest {
    private fun entry(type:String):ServerImport.Server=ServerImport.Server(
        original="owned-$type",name=type,protocol=type,host="192.0.2.7",port=443,
        outbound=JSONObject().put("type",type).put("server","192.0.2.7").put("server_port",443),
        endpoint=null,configuration=null
    )

    @Test fun exactCompactOutboundsAreRecognizedWithoutCarrierMetadata() {
        for(type in listOf("tuic","anytls","naive","ssh")){
            val entry=entry(type)
            val tag=ServerImport.tag(entry)
            val source=ServerImport.build(listOf(entry),entry.id,false,60,50,"Cloudflare")
            assertTrue(StandaloneRouterSelection.owns(source,entry))
            assertFalse(source.has("mcrf_carrier"))
            val node=source.getJSONArray("outbounds").getJSONObject(1)
            node.put("server_port",444)
            assertFalse(StandaloneRouterSelection.owns(source,entry))
            node.put("server_port",443)
            source.getJSONObject("route").put("final","direct")
            assertFalse(StandaloneRouterSelection.owns(source,entry))
            source.getJSONObject("route").put("final",tag)
            source.getJSONArray("outbounds").put(JSONObject(node.toString()))
            assertFalse(StandaloneRouterSelection.owns(source,entry))
        }
    }

    @Test fun placeholderCoversOnlySelectedTagAndLeavesOtherPolicyUntouched(){
        val entry=entry("tuic")
        val source=ServerImport.build(listOf(entry),entry.id,false,60,50,"Cloudflare")
        val tag=ServerImport.tag(entry)
        val dns=source.getJSONObject("dns").toString()
        val direct=source.getJSONArray("outbounds").getJSONObject(0).toString()
        BsNativeProfilePreparation.replaceStandaloneOutbound(source,tag)
        val replacement=source.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("socks",replacement.getString("type"))
        assertEquals("127.0.0.1",replacement.getString("server"))
        assertEquals(1,replacement.getInt("server_port"))
        assertEquals(tag,source.getJSONObject("route").getString("final"))
        assertEquals(dns,source.getJSONObject("dns").toString())
        assertEquals(direct,source.getJSONArray("outbounds").getJSONObject(0).toString())
        assertFalse(source.has("mcrf_carrier"))
    }
}
