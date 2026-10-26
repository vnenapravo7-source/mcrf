package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FixedFinalReviewTest {
    private fun config(final:String)=JSONObject().put("route",JSONObject().put("final",final))
        .put("outbounds",JSONArray().put(JSONObject().put("tag","profile").put("type","socks"))
            .put(JSONObject().put("tag","direct").put("type","direct"))
            .put(JSONObject().put("tag","group").put("type","selector").put("outbounds",JSONArray().put("profile"))))
    @Test fun actualDirectFinalIsNotTheOriginalProfileTarget() {
        val source=config("direct")
        assertEquals("profile",OutboundObservation.fixed(source,"profile"))
        assertEquals("direct",OutboundObservation.fixedFinal(source))
    }
    @Test fun concreteOptionalFinalIsIdentified() {
        assertEquals("profile",OutboundObservation.fixedFinal(config("profile")))
    }
    @Test fun dynamicFinalDoesNotGuessOneOfItsMembers() {
        assertNull(OutboundObservation.fixedFinal(config("group")))
    }
    @Test fun missingFinalOrConfigDoesNotMatchAnyWorker() {
        assertNull(OutboundObservation.fixedFinal(null))
        assertNull(OutboundObservation.fixedFinal(config("missing")))
        assertNull(OutboundObservation.fixedFinal(config("profile").apply {remove("route")}))
    }
}
