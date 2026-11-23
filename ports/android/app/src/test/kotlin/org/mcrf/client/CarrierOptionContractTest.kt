package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CarrierOptionContractTest {
    private fun modern(implementation:String)=JSONObject().put("type","csqtt").put("csqtt_generation","2.5")
        .put("csqtt_implementation",implementation)
    @Test fun explicitNonautoSrtpRequiresNewFeatureEvenWhenOldPanelAndVersionCapabilitiesExist() {
        for(implementation in listOf("compatible-2.5","swg-panel-2.5.1","swg-panel-2.5.2","swg-panel-2.5.3")) {
            for(mode in listOf("wrap-srtp","dtls-srtp")) {
                val profile=modern(implementation).put("srtp_mode",mode)
                val before=profile.toString()
                assertTrue(CarrierOptionContract.requiredAll(profile).contains(CarrierOptionContract.SRTP_MODE))
                // Installed BS.4 and old dedicated modules lack this new capability.
                assertTrue(CarrierOptionContract.missing(profile){if(it==CarrierOptionContract.SRTP_MODE)0 else 1})
                assertTrue(CarrierOptionContract.missing(profile){if(it==CarrierOptionContract.SRTP_MODE)-1 else 1})
                assertFalse(CarrierOptionContract.missing(profile){1})
                assertEquals(before,profile.toString())
            }
        }
    }
    @Test fun omittedAndExplicitAutoKeepOldCapabilityRequirements() {
        for(implementation in listOf("compatible-2.5","swg-panel-2.5.1","swg-panel-2.5.2","swg-panel-2.5.3")) {
            val old=modern(implementation)
            val baseline=CarrierOptionContract.requiredAll(old)
            assertFalse(baseline.contains(CarrierOptionContract.SRTP_MODE))
            assertEquals(baseline,CarrierOptionContract.requiredAll(modern(implementation).put("srtp_mode","auto")))
            assertFalse(CarrierOptionContract.missing(old){if(it==CarrierOptionContract.SRTP_MODE)0 else 1})
        }
    }
    @Test fun malformedSrtpCannotBypassAdmissionAndLegacyProfilesGainNoModernCapability() {
        for(value in listOf<Any>(JSONObject.NULL,"","AUTO","dtls",true,1)) {
            assertThrows(IllegalArgumentException::class.java) {
                CarrierOptionContract.requiredAll(modern("swg-panel-2.5.3").put("srtp_mode",value))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarrierOptionContract.requiredAll(modern("compatible-2.5").put(CsqttGenerations.SELECTION,CsqttGenerations.AUTO).put("srtp_mode","dtls-srtp"))
        }
        assertFalse(CarrierOptionContract.requiredAll(JSONObject().put("type","csqtt")).contains(CarrierOptionContract.SRTP_MODE))
    }
    @Test fun oldDefaultsNeedNoNewCapabilityButEnabledOptionsDo() {
        assertNull(CarrierOptionContract.required(JSONObject().put("type","csqtt")))
        assertNull(CarrierOptionContract.required(JSONObject().put("type","wdtt")))
        assertNull(CarrierOptionContract.required(JSONObject().put("type","qwdtt")))
        assertEquals("org.mcrf.engines.qwdtt_fingerprint",CarrierOptionContract.required(JSONObject().put("type","qwdtt").put("fingerprint","safari")))
        assertEquals("org.mcrf.engines.csqtt_account_options",CarrierOptionContract.required(JSONObject().put("type","csqtt").put("vk_anon_path","auto_js")))
        assertEquals("org.mcrf.engines.csqtt_account_options",CarrierOptionContract.required(JSONObject().put("type","csqtt").put("fingerprint","firefox")))
        assertEquals("org.mcrf.engines.wdtt_provider_options",CarrierOptionContract.required(JSONObject().put("type","wdtt").put("custom_vk_client_id","123")))
        assertEquals("org.mcrf.engines.wdtt_provider_options",CarrierOptionContract.required(JSONObject().put("type","wdtt").put("turn_stream_first",true)))
    }
}
