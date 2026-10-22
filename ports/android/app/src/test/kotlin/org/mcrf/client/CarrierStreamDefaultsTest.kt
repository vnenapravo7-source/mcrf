package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CarrierStreamDefaultsTest {
    @Test fun editedServerExportPreservesManualOverrideButDoesNotInventOneForDefault() {
        val raw=JSONObject().put("type","csqtt").put("server","fixture.invalid").put("server_port",56000)
            .put("password","fixture-only").put("hashes",JSONArray().put("fixture-call-0123456789abcdef")).put("workers",18)
        assertFalse(SwgProfile.fromJson("default",raw).carrier!!.has(CarrierStreamDefaults.OVERRIDE))
        raw.put(CarrierStreamDefaults.OVERRIDE,27)
        val shared=SwgProfile.share(raw)
        assertEquals(27,SwgProfile.fromJson(shared,JSONObject(shared)).carrier!!.getInt(CarrierStreamDefaults.OVERRIDE))
    }
    private fun profile(kind:String)=JSONObject().put("type",kind).put("hashes",JSONArray(listOf("fixture-call")))
    @Test fun missingOverrideUsesGlobalAndDoesNotMutateStoredProfile() {
        val source=profile("csqtt").put("workers",9)
        val result=CarrierStreamDefaults.apply(source,JSONObject().put("csqtt",27))
        assertEquals(27,result.getInt("workers"));assertEquals(9,source.getInt("workers"))
        assertFalse(source.has(CarrierStreamDefaults.OVERRIDE))
    }
    @Test fun manualValueWinsOnlyForItsServerAndRejectsUnavailableCount() {
        val source=profile("wdtt").put(CarrierStreamDefaults.OVERRIDE,18)
        assertEquals(18,CarrierStreamDefaults.apply(source,JSONObject().put("wdtt",9)).getInt("workers"))
        assertEquals(9,CarrierStreamDefaults.apply(profile("wdtt"),JSONObject().put("wdtt",9)).getInt("workers"))
        try{CarrierStreamDefaults.apply(source.put(CarrierStreamDefaults.OVERRIDE,36),JSONObject());fail("impossible local count accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun globalCountClampsToAvailableCallsAndOldGenerationKeepsStepNine() {
        assertEquals(27,CarrierStreamDefaults.apply(profile("csqtt"),JSONObject().put("csqtt",126)).getInt("workers"))
        try{CarrierStreamDefaults.validate(JSONObject().put("csqtt",20));fail("wrong generation step accepted")}catch(_:IllegalArgumentException){}
        assertEquals((10..100 step 10).toList(),CarrierStreamDefaults.values("csqtt25"))
    }
    @Test fun exact25OverrideIsNotRoundedIntoLegacyStepOrKeptInNativeConfig() {
        val source=profile("csqtt").put("csqtt_generation","2.5").put(CarrierStreamDefaults.OVERRIDE,7)
        val result=CarrierStreamDefaults.apply(source,JSONObject())
        assertEquals(7,result.getInt("workers"));assertEquals(7,result.getInt("link_workers"))
        assertFalse(result.has(CarrierStreamDefaults.OVERRIDE))
    }
}
