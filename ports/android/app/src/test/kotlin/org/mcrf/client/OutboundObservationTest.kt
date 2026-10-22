package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OutboundObservationTest {
    @Test fun concreteOutboundDoesNotNeedPollingButLiveGroupsStillDo() {
        fun config(type:String)=JSONObject().put("outbounds",JSONArray().put(JSONObject().put("tag","main").put("type",type)))
        for(type in listOf("socks","vless","direct","trojan"))assertEquals("main",OutboundObservation.fixed(config(type),"main"))
        for(type in listOf("selector","urltest"))assertNull(OutboundObservation.fixed(config(type),"main"))
        assertNull(OutboundObservation.fixed(config("socks"),"missing"));assertNull(OutboundObservation.fixed(null,"main"))
        assertNull(OutboundObservation.fixed(JSONObject(),"main"))
    }
}
