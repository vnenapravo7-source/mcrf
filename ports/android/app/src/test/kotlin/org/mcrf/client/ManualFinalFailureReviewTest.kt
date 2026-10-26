package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManualFinalFailureReviewTest {
    private fun group(tag:String,type:String="selector",vararg members:String)=JSONObject().put("tag",tag).put("type",type).put("outbounds",JSONArray(members.toList()))
    private fun config(vararg extra:JSONObject)=JSONObject().put("route",JSONObject().put("final","main"))
        .put("outbounds",JSONArray().put(group("main","selector","one","two"))
            .put(JSONObject().put("tag","one").put("type","socks"))
            .put(JSONObject().put("tag","two").put("type","socks"))
            .put(JSONObject().put("tag","route-only").put("type","socks")).apply {extra.forEach {put(it)}})
    @Test fun actualSelectedDeadManualMemberIsReported() {
        var calls=0
        assertEquals("one",OutboundObservation.failedManualFinal(config(),false,setOf("one")){calls++;"one"})
        assertEquals(1,calls)
    }
    @Test fun failedUnselectedMemberDoesNotRetireHealthySelection() {
        assertNull(OutboundObservation.failedManualFinal(config(),false,setOf("one")){"two"})
    }
    @Test fun perRuleOnlyDeathDoesNotEvenQueryPrimaryGroup() {
        assertNull(OutboundObservation.failedManualFinal(config(),false,setOf("route-only")){fail("No RPC for unrelated rule");""})
    }
    @Test fun compiledDirectDefaultDoesNotUseOriginalProfileSelection() {
        val source=config(JSONObject().put("tag","direct").put("type","direct"))
        source.getJSONObject("route").put("final","direct")
        assertNull(OutboundObservation.failedManualFinal(source,false,setOf("one")){fail("No RPC for direct final");""})
    }
    @Test fun automaticModeDoesNotOverrideItsGroupPolicy() {
        assertNull(OutboundObservation.failedManualFinal(config(),true,setOf("one")){fail("No manual RPC in AUTO");""})
    }
    @Test fun nestedUrltestIsNotTreatedAsManualSelection() {
        val source=config(group("automatic","urltest","one","two"))
        source.getJSONObject("route").put("final","automatic")
        assertNull(OutboundObservation.failedManualFinal(source,false,setOf("one")){fail("No urltest guess");""})
    }
    @Test fun nestedManualSelectorsUseEachActualObservation() {
        val source=config(group("outer","selector","main"))
        source.getJSONObject("route").put("final","outer")
        val observed=mutableListOf<String>()
        assertEquals("one",OutboundObservation.failedManualFinal(source,false,setOf("one")){observed+=it;if(it=="outer")"main"else"one"})
        assertEquals(listOf("outer","main"),observed)
    }
    @Test fun staleReplyOrCycleNeverGuessesADeadMember() {
        assertNull(OutboundObservation.failedManualFinal(config(),false,setOf("one")){"unlisted"})
        val source=config(group("cycle","selector","cycle","one"))
        source.getJSONObject("route").put("final","cycle")
        var calls=0
        assertNull(OutboundObservation.failedManualFinal(source,false,setOf("one")){calls++;"cycle"})
        assertEquals(1,calls)
    }
}
