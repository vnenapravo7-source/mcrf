package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray

class CarrierCaptchaModeTest {
    @Test fun twoUserChoicesMapToActualNativeModes() {
        assertTrue(CarrierCaptchaMode.automatic("auto"))
        assertTrue(CarrierCaptchaMode.automatic("rjs"))
        assertFalse(CarrierCaptchaMode.automatic("wv"))
        assertEquals("wv",CarrierCaptchaMode.select("auto",false))
        assertEquals("auto",CarrierCaptchaMode.select("wv",true))
    }
    @Test fun openingOrSavingAnotherFieldPreservesLegacyAutomaticMode() {
        assertEquals("rjs",CarrierCaptchaMode.select("rjs",true))
        assertEquals("auto",CarrierCaptchaMode.select("auto",true))
        assertEquals("wv",CarrierCaptchaMode.select("wv",false))
        assertEquals("wv",CarrierCaptchaMode.select("rjs",false))
    }
    @Test fun unknownWireValueCannotBeSilentlyRewritten() {
        assertThrows(IllegalArgumentException::class.java) {CarrierCaptchaMode.select("future-mode",true)}
    }
    @Test fun catalogueRoundTripChangesOnlyCaptchaAndPreservesIdentityAndSecrets() {
        for(type in SwgProfile.TYPES) {
            val profile=JSONObject().put("type",type).put("server","fixture.invalid").put("server_port",56000)
                .put("password","fixture-only").put("hashes",JSONArray().put("fixture-call-0123456789abcdef"))
                .put("captcha_mode","rjs").put("device_id","existing-fixture-binding").put("workers",18)
            val source=SwgProfile.share(profile)
            val catalogue=ConnectionCatalogue("");catalogue.add(source)
            val before=catalogue.entries().single();val original=before.carrier!!.toString()
            val options=ConnectionEdits.captcha(before,false)
            assertEquals(original,before.carrier!!.toString())
            catalogue.edit(before.id,options)
            val restored=ConnectionCatalogue(catalogue.serialize()).entries().single()
            assertEquals(before.id,restored.id);assertEquals(before.original,restored.original)
            assertEquals("wv",restored.carrier!!.getString("captcha_mode"))
            val expected=JSONObject(original).put("captcha_mode","wv")
            assertEquals(expected.toString(),restored.carrier!!.toString())
            val shared=ServerImport.read(ConnectionEdits.share(restored,true)).single()
            assertEquals("wv",shared.carrier!!.getString("captcha_mode"))
        }
    }
}
