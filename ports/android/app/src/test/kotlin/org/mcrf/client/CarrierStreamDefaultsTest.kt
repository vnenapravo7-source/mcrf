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
    private fun sixCallProfile(implementation:String)=JSONObject().put("type","csqtt").put("csqtt_generation","2.5")
        .put("csqtt_implementation",implementation).put("vk_hash_mode","manual")
        .put("hashes",JSONArray((1..6).map {"fixture-call-0123456789-$it"}))
        .put("workers",20).put("link_workers",0).put("allow_hash_redistribution",false)
        .put("captcha_mode","wv").put("fingerprint","firefox").put("turn_transport","tcp")
    @Test fun swg252253ManualSixHashesKeepEightyAndOneHundredWithoutRedistribution() {
        for(implementation in CsqttSwgVersions.revisions.keys)for(count in listOf(80,100)) {
            val source=sixCallProfile(implementation);val settings=JSONObject().put("csqtt25",count)
            val before=source.toString();val settingsBefore=settings.toString()
            val effective=CarrierStreamDefaults.apply(source,settings)
            assertEquals(count,effective.getInt("workers"));assertEquals(count,effective.getInt("link_workers"))
            assertTrue(CarrierStreamDefaults.usesExtendedCsqtt25Range(effective))
            assertFalse(effective.getBoolean("allow_hash_redistribution"))
            for(key in listOf("captcha_mode","fingerprint","turn_transport","hashes","csqtt_implementation"))
                assertEquals(source.get(key).toString(),effective.get(key).toString())
            assertEquals(before,source.toString());assertEquals(settingsBefore,settings.toString())
        }
    }
    @Test fun swg252253ExplicitOverridesWinWithoutEnablingRedistributionOrForcingSeventy() {
        for(implementation in CsqttSwgVersions.revisions.keys)for(count in listOf(7,60,70,80,100)) {
            val source=sixCallProfile(implementation).put(CarrierStreamDefaults.OVERRIDE,count)
            val before=source.toString()
            val effective=CarrierStreamDefaults.apply(source,JSONObject().put("csqtt25",100))
            assertEquals(count,effective.getInt("workers"));assertEquals(count,effective.getInt("link_workers"))
            assertEquals(count>70,CarrierStreamDefaults.usesExtendedCsqtt25Range(effective))
            assertFalse(effective.has(CarrierStreamDefaults.OVERRIDE));assertEquals(before,source.toString())
        }
    }
    @Test fun modernCapacityDoesNotInventAnAbsentRedistributionChoice() {
        for(implementation in CsqttSwgVersions.revisions.keys) {
            val source=sixCallProfile(implementation).apply {remove("allow_hash_redistribution")}
            val before=source.toString()
            val effective=CarrierStreamDefaults.apply(source,JSONObject().put("csqtt25",80))
            assertEquals(80,effective.getInt("workers"))
            assertFalse(effective.has("allow_hash_redistribution"));assertEquals(before,source.toString())
        }
    }
    @Test fun earlierImplementationsKeepManualCallCapacityAndRejectOversizedOverrides() {
        for(implementation in listOf("swg-panel-2.5.1","compatible-2.5")) {
            val source=sixCallProfile(implementation);val before=source.toString()
            assertEquals(60,CarrierStreamDefaults.apply(source,JSONObject().put("csqtt25",80)).getInt("workers"))
            assertFalse(CarrierStreamDefaults.usesExtendedCsqtt25Range(source))
            assertEquals(before,source.toString())
            assertThrows(IllegalArgumentException::class.java) {
                CarrierStreamDefaults.apply(JSONObject(source.toString()).put(CarrierStreamDefaults.OVERRIDE,100),JSONObject())
            }
            val redistributed=CarrierStreamDefaults.apply(JSONObject(source.toString()).put("allow_hash_redistribution",true),JSONObject().put("csqtt25",100))
            assertEquals(100,redistributed.getInt("workers"));assertTrue(redistributed.getBoolean("allow_hash_redistribution"))
        }
        val legacy=profile("csqtt").put("workers",18);val before=legacy.toString()
        assertEquals(27,CarrierStreamDefaults.apply(legacy,JSONObject().put("csqtt",126)).getInt("workers"))
        assertEquals(before,legacy.toString())
    }
}
