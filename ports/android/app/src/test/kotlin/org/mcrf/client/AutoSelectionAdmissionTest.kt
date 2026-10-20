package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutoSelectionAdmissionTest {
    private val now=1_000_000L
    private fun entry(name:String,carrier:JSONObject?,configuration:JSONObject?=null)=
        ServerImport.Server("fixture:$name",name,"csqtt","fixture.invalid",443,null,null,configuration,carrier)
    private fun chosen(vararg entries:ServerImport.Server):List<ServerImport.Server> {
        val probes=JSONObject().apply {entries.forEach {put(it.id,JSONObject().put("delay",1).put("at",now))}}
        return AutoSelection.proven(entries.toList(),entries.map{it.id}.toSet(),probes,now,AutoSelection.BS_ENGINES)
    }
    @Test fun measuredProtocolNameWithoutCarrierIsRejected(){
        val invalid=entry("missing",null)
        assertFalse(AutoSelection.eligible(invalid,AutoSelection.BS_ENGINES));assertTrue(chosen(invalid).isEmpty())
    }
    @Test fun measuredJsonProfileCannotEnterCarrierPool(){
        val invalid=entry("json",JSONObject().put("type","csqtt"),JSONObject())
        assertFalse(AutoSelection.eligible(invalid,AutoSelection.BS_ENGINES));assertTrue(chosen(invalid).isEmpty())
    }
    @Test fun invalidMeasuredEntriesDoNotPoisonValidPool(){
        val valid=entry("valid",JSONObject().put("type","csqtt"))
        assertEquals(listOf(valid),chosen(entry("missing",null),valid,entry("json",JSONObject(),JSONObject())))
    }
    @Test fun generationIsKeptInProfileNotInventedAsDifferentProtocol(){
        val profile=JSONObject().put("type","csqtt").put("csqtt_generation","2.5")
        val modern=entry("modern",profile)
        assertEquals(listOf(modern),chosen(modern));assertSame(profile,modern.carrier)
        assertEquals("csqtt",modern.protocol)
    }
    @Test fun legacyProfileIsNotRewrittenOrSubstituted(){
        val profile=JSONObject().put("type","csqtt").put("csqtt_generation","legacy")
        val legacy=entry("legacy",profile)
        assertEquals(listOf(legacy),chosen(legacy));assertSame(profile,legacy.carrier)
        assertEquals("legacy",legacy.carrier!!.getString("csqtt_generation"))
    }
}
