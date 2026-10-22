package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CarrierOptionContractTest {
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
