package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MasterDnsProfileTest {
    private fun profile()=JSONObject().put("type","masterdnsvpn").put("domains",JSONArray().put("t.example.test"))
        .put("resolvers",JSONArray().put("127.0.0.1:5353")).put("encryption_method",5).put("encryption_key","owned key with spaces")
    @Test fun profileUsesOriginalProtocolAndDedicatedModule() {
        val server=ServerImport.read(profile().toString()).single()
        assertEquals("masterdnsvpn",server.protocol);assertTrue(server.needsEngine())
        assertEquals(setOf("libmasterdnsvpn.so"),EngineModules.libraries("masterdnsvpn"))
        assertTrue(MasterDnsProfile.compatible("masterdnsvpn",MasterDnsProfile.REPOSITORY,MasterDnsProfile.COMMIT,1))
        assertFalse(MasterDnsProfile.compatible("dnstt",MasterDnsProfile.REPOSITORY,MasterDnsProfile.COMMIT,1))
        assertFalse(MasterDnsProfile.compatible("masterdnsvpn",MasterDnsProfile.REPOSITORY,MasterDnsProfile.COMMIT,0))
    }
    @Test fun runtimeConfigDoesNotLeakDisplayOrProbeMetadata() {
        val data=MasterDnsProfile.privateConfig(profile().put("probe_only",true).put("outbound_tag","fixture"),12345,"local","local-private")
        assertFalse(data.has("type"));assertFalse(data.has("name"));assertFalse(data.has("probe_only"));assertFalse(data.has("outbound_tag"));assertFalse(data.has("device_id"))
        assertEquals("local",data.getString("socks_user"));assertEquals(2,data.getInt("workers"));assertEquals(5,data.getInt("encryption_method"))
    }
    @Test fun importCannotPickCliPathsOrSilentlySelectWeakEncryption() {
        for(key in listOf("socks","socks_user","config_path","resolvers_file","log")) {
            try {MasterDnsProfile.fromJson("fixture",profile().put(key,"untrusted"));fail("CLI override accepted")}catch(_:IllegalArgumentException){}
        }
        val missing=profile().apply {remove("encryption_method")}
        try {MasterDnsProfile.fromJson("fixture",missing);fail("implicit encryption accepted")}catch(_:IllegalArgumentException){}
        try {MasterDnsProfile.fromJson("fixture",profile().put("encryption_key",123));fail("numeric key accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun completeEncryptionKeyIsRedactedIncludingSpaces() {
        val key="owned key with spaces"
        val clean=ClientEvents.redact("{\"encryption_key\":\"$key\",\"workers\":2}")
        assertFalse(clean.contains(key));assertFalse(clean.contains("with spaces"));assertTrue(clean.contains("workers"))
        val detail=CarrierFailureDetail("masterdnsvpn",profile())
        detail.offer("failed to use $key")
        assertFalse(detail.describe("fixture",1).contains(key))
        assertNull(MasterDnsFailure.describe("masterdnsvpn","MCRF_ERROR MASTERDNS_RUNTIME key=$key"))
    }
}
