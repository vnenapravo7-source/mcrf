package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SstpProfileTest {
    @Test fun linkCanEnterTheAppButCredentialsCannotEnterLogs() {
        val link="sstp://fixture:private-password@vpn.example.test?sni=cert.example.test"
        assertEquals(link,ImportLink.config(link))
        assertFalse(ClientEvents.redact(link).contains("private-password"))
    }
    private fun profile()=JSONObject().put("type","sstp").put("server","vpn.example.test").put("username","fixture").put("password","private")
    @Test fun jsonAndNativeConfigUseDedicatedVerifiedModule() {
        val server=ServerImport.read(profile().toString()).single()
        assertEquals("sstp",server.protocol);assertEquals(443,server.port);assertTrue(server.needsEngine())
        assertEquals(setOf("libsstp.so"),EngineModules.libraries("sstp"))
        assertTrue(SstpProfile.compatible("sstp",SstpProfile.REPOSITORY,SstpProfile.COMMIT,1))
        assertFalse(SstpProfile.compatible("sstp",SstpProfile.REPOSITORY,SstpProfile.COMMIT,0))
        val config=SstpProfile.privateConfig(requireNotNull(server.carrier).put("probe_only",true).put("outbound_tag","fixture"),12345,"local","local-password")
        assertFalse(config.has("type"));assertFalse(config.has("name"));assertFalse(config.has("probe_only"));assertFalse(config.has("device_id"));assertFalse(config.has("outbound_tag"))
        assertEquals("local",config.getString("socks_user"))
    }
    @Test fun upstreamStyleUriPreservesEncodedCredentialsAndSni() {
        val server=ServerImport.read("sstp://user%2Bname:p%3Aa%2Bss@vpn.example.test:444?sni=cert.example.test#SSTP%20fixture").single()
        val value=requireNotNull(server.carrier)
        assertEquals(444,server.port);assertEquals("user+name",value.getString("username"));assertEquals("p:a+ss",value.getString("password"));assertEquals("cert.example.test",value.getString("sni"))
        assertEquals("SSTP fixture",server.name)
    }
    @Test fun invalidOrInsecureProfilesFailWithoutFallback() {
        for(value in listOf(profile().put("allow_insecure",true),profile().put("server_port",0),profile().put("server_port",443.5),profile().put("server","https://vpn.example.test/path"),profile().put("socks","0.0.0.0:1"))) {
            try {SstpProfile.fromJson("fixture",value);fail("unsafe profile accepted")}catch(_:IllegalArgumentException){}
        }
        try {SstpProfile.link("sstp://u:p@vpn.example.test?insecure=1");fail("TLS bypass accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun failuresShowSafeSpecificStage() {
        assertTrue(requireNotNull(SstpFailure.describe("sstp","MCRF_ERROR SSTP_TLS_NAME_MISMATCH")).contains("SNI"))
        assertNotNull(SstpFailure.describe("sstp","MCRF_ERROR SSTP_AUTH_REJECTED"))
        assertNull(SstpFailure.describe("sstp","MCRF_ERROR SSTP_AUTH_REJECTED password=private"))
        assertNull(SstpFailure.describe("dnstt","MCRF_ERROR SSTP_AUTH_REJECTED"))
    }
}
