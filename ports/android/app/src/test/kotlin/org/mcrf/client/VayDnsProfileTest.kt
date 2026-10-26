package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VayDnsProfileTest {
    @Test fun moduleContractRejectsOtherSourcesAndUnprotectedInterfaces() {
        assertTrue(VayDnsProfile.compatible("vaydns",VayDnsProfile.REPOSITORY,VayDnsProfile.COMMIT,1))
        assertFalse(VayDnsProfile.compatible("dnstt",VayDnsProfile.REPOSITORY,VayDnsProfile.COMMIT,1))
        assertFalse(VayDnsProfile.compatible("vaydns","fixture-other-source",VayDnsProfile.COMMIT,1))
        assertFalse(VayDnsProfile.compatible("vaydns",VayDnsProfile.REPOSITORY,"0".repeat(40),1))
        assertFalse(VayDnsProfile.compatible("vaydns",VayDnsProfile.REPOSITORY,VayDnsProfile.COMMIT,0))
    }
    private fun profile()=JSONObject().put("type","vaydns").put("name","Vay fixture")
        .put("domain","t.example.test").put("key","01".repeat(32)).put("udp","1.1.1.1:53")
    @Test fun jsonImportUsesDedicatedCarrierAndModule() {
        val server=ServerImport.read(profile().toString()).single()
        assertEquals("vaydns",server.protocol)
        assertTrue(server.needsEngine())
        assertEquals("vaydns",EngineModules.carrierKind(requireNotNull(server.carrier)))
        assertEquals("org.mcrf.engines.vaydns",EngineModules.packageName("vaydns"))
        assertEquals(setOf("libvaydns.so"),EngineModules.libraries("vaydns"))
        assertFalse(EngineModules.validNativeEntries("vaydns","arm64-v8a",listOf("lib/arm64-v8a/libdnstt.so")))
    }
    @Test fun privateConfigDoesNotLeakDisplayOrProbeMetadata() {
        val value=VayDnsProfile.privateConfig(profile().put("probe_only",true).put("outbound_tag","owned-route"),12345,"local","private")
        assertFalse(value.has("type"));assertFalse(value.has("name"));assertFalse(value.has("probe_only"));assertFalse(value.has("device_id"))
        assertFalse(value.has("outbound_tag"))
        assertEquals("127.0.0.1:12345",value.getString("socks"))
        assertEquals("local",value.getString("socks_user"))
        assertEquals("private",value.getString("socks_pass"))
    }
    @Test fun importedProfileCannotOverrideRuntimeCredentialsOrTlsPolicy() {
        for(key in listOf("socks","socks_user","socks_pass","insecure","probe_only")) {
            try {VayDnsProfile.fromJson("fixture",profile().put(key,"untrusted"));fail("override accepted: $key")}
            catch(_:IllegalArgumentException){}
        }
    }
}
