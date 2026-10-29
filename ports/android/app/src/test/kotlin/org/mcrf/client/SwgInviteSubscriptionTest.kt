package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SwgInviteSubscriptionTest {
    @Test fun addressRequiresCompletePanelLinkAndKeepsUnlockFragment(){
        val key=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){it.toByte()})
        val value="https://vnenapravo7.ru/wdtt/sub/abcdefghijklmnop#$key"
        assertEquals(value,SwgInviteSubscription.address(value))
    }
    @Test fun panelTargetsBecomeEverySupportedGeneration(){
        fun target(kind:String,index:Int)=JSONObject().put("type",kind).put(kind,JSONObject()
            .put("endpoint_host","server$index.example").put("password","secret$index").put("vk_hash","abcdefghijklmnop$index")
            .put(if(kind=="csqtt")"port" else "dtls_port",55000+index)
            .put("wg_port",56000+index).put("tun_port",9000+index)
            .put("fork",if(index<3)"qwdtt" else "wdttplus").put("version",if(index<3)"2.1.9-4" else "2.5.1-2"))
        val targets=JSONArray();(0 until 6).forEach {targets.put(target("wdtt",it))};(0 until 6).forEach {targets.put(target("csqtt",it))}
        val response=JSONObject().put("ok",true).put("data",JSONObject().put("vk_links",JSONArray()).put("peers",JSONArray().put(JSONObject().put("targets",targets))))
        val parsed=ServerImport.read(SwgInviteSubscription.links(response))
        val groups=parsed.groupingBy {it.carrier?.optString("type").orEmpty() to it.carrier?.optString("csqtt_generation").orEmpty()}.eachCount()
        assertEquals(3,groups["qwdtt" to ""]);assertEquals(3,groups["wdtt" to ""]);assertEquals(3,groups["csqtt" to ""]);assertEquals(3,groups["csqtt" to "2.5"])
    }
}
