package org.mcrf.client

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CsqttNativeEventsTest {
    @Test fun largeServerRoutingMustArriveComplete() {
        val routing=JSONObject().put("exclude",org.json.JSONArray((0..800).map {"example$it.invalid"})).toString()
        val notice="__CSQTT_EVENT__|ROUTING|"+JSONObject().put("routing",routing)
        assertTrue(notice.length>4096)
        val truncated=notice.take(4096)
        try {JSONObject(truncated.substringAfter('|').substringAfter('|'));fail("Fixture must reproduce truncated JSON")}
        catch(_:JSONException){}
        try {CsqttNativeEvents.read(truncated);fail("A truncated routing policy must fail closed")}
        catch(_:JSONException){}
        assertEquals(routing,CsqttNativeEvents.read(notice)!!.data.getString("routing"))
        assertEquals(7,CsqttNativeEvents.read("__CSQTT_EVENT__|STATS|{\"active\":7}")!!.data.getInt("active"))
    }
    @Test(expected=JSONException::class) fun malformedConsumedCommandStillFailsClosed() {
        CsqttNativeEvents.read("__CSQTT_EVENT__|CONFIG|{\"config\":\"truncated")
    }
    @Test fun fatalErrorsAndLossRemainVisible() {
        val error=CsqttNativeEvents.read("__CSQTT_EVENT__|ERROR|{\"fatal\":true}")!!
        assertEquals("ERROR",error.kind);assertTrue(error.data.getBoolean("fatal"))
        assertEquals("TUN_LOST",CsqttNativeEvents.read("__CSQTT_EVENT__|TUN_LOST|{}")!!.kind)
    }
    @Test fun payloadDelimiterIsPreservedAndUnconsumedEventsAreNotCommands() {
        assertEquals("a|b",CsqttNativeEvents.read("__CSQTT_EVENT__|PROGRESS|{\"kind\":\"a|b\"}")!!.data.getString("kind"))
        assertNull(CsqttNativeEvents.read("__CSQTT_EVENT__|FUTURE_NOTICE|not-json"))
        assertNull(CsqttNativeEvents.read("unrelated stdout"))
    }
}
