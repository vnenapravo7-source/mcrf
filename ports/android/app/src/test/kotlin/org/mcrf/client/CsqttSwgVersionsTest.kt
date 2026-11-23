package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CsqttSwgVersionsTest {
    private fun profile(implementation:String)=JSONObject().put("type","csqtt").put("csqtt_generation","2.5")
        .put("csqtt_implementation",implementation).put("server","fixture.invalid").put("server_port",46000)
        .put("password","fixture-only").put("hashes",JSONArray().put("fixture-call-0123456789abcdef"))
    @Test fun explicitVersionsSurviveImportSharingAndPrivateConfig(){
        for(version in listOf("2.5.2","2.5.3")) {
            val id="swg-panel-$version"
            assertTrue(CsqttGenerations.importChoices.containsKey(id))
            val p=Csqtt25Profile.fromJson("fixture",profile(id)).carrier!!
            assertEquals("CSQTT v$version · SWG",CsqttGenerations.label(p))
            assertEquals("csqtt25",EngineModules.carrierKind(p))
            val imported=ServerImport.read(CsqttGenerations.applyImportChoice(profile("swg-panel-2.5.1").toString(),id)).single().carrier!!
            assertEquals(id,imported.getString("csqtt_implementation"))
            assertEquals(id,Csqtt25Profile.privateConfig(p,22001,22002,"u","p","installation").getString("csqtt_implementation"))
            assertTrue(CarrierOptionContract.requiredAll(p).contains(CsqttSwgVersions.CAPABILITY))
        }
    }
    @Test fun oldDefaultsRemainOldAndUnknownVersionsFailClosed(){
        val old=profile("swg-panel-2.5.1")
        assertFalse(CarrierOptionContract.requiredAll(old).contains(CsqttSwgVersions.CAPABILITY))
        assertFalse(Csqtt25Profile.privateConfig(old,22001,22002,"u","p","installation").has("csqtt_implementation"))
        assertThrows(IllegalArgumentException::class.java){Csqtt25Profile.fromJson("fixture",profile("swg-panel-2.5.4"))}
    }
    @Test fun moduleAbiGateAllowsOnlyCompleteKnownVersionedSets(){
        val old=EngineModules.libraries("csqtt25").map{"lib/arm64-v8a/$it"}
        assertTrue(EngineModules.validNativeEntries("csqtt25","arm64-v8a",old))
        val expanded=old+"lib/arm64-v8a/${CsqttSwgVersions.LIBRARY}"
        assertTrue(EngineModules.validNativeEntries("csqtt25","arm64-v8a",expanded))
        assertFalse(EngineModules.validNativeEntries("csqtt25","x86_64",expanded))
        assertFalse(EngineModules.validNativeEntries("csqtt","arm64-v8a",expanded))
        assertFalse(EngineModules.validNativeEntries("csqtt25","arm64-v8a",expanded+"lib/arm64-v8a/libunknown.so"))
        assertFalse(EngineModules.validNativeEntries("csqtt25","arm64-v8a",expanded+expanded.last()))
    }
}
