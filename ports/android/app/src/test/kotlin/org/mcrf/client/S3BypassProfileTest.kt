package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class S3BypassProfileTest {
    private fun profile()=JSONObject("""{"type":"s3bypass","name":"Fixture S3","settings":{"storage":{"type":"s3","bucket":"fixture-bucket","endpoint":"https://storage.fixture.invalid","region":"fixture-region","prefix":"fedarisha/fixture/","accessKey":"fixture-access-value","secretKey":"fixture-secret-value"}}}""")
    @Test fun distinctCarrierUsesAuthenticatedLoopbackAndFedarishaOnly(){
        val input=profile();val entry=S3BypassProfile.fromJson(input.toString(),input)
        assertEquals(S3BypassProfile.KIND,entry.protocol);assertTrue(entry.needsEngine());assertTrue(ConnectionSections.belongs(entry,true))
        val config=S3BypassProfile.privateConfig(entry.carrier!!,12345,"fixture-user","fixture-pass")
        val inbound=config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1",inbound.getString("listen"));assertEquals("password",inbound.getJSONObject("settings").getString("auth"))
        val out=config.getJSONArray("outbounds");assertEquals(1,out.length());assertEquals("fedarisha",out.getJSONObject(0).getString("protocol"))
    }
    @Test fun unsafeEndpointsAndStorageTypesAreRejected(){
        for(endpoint in listOf("http://storage.fixture.invalid","https://user:pass@storage.fixture.invalid","https://storage.fixture.invalid/?key=fixture")){
            val input=profile();input.getJSONObject("settings").getJSONObject("storage").put("endpoint",endpoint)
            assertThrows(IllegalArgumentException::class.java){S3BypassProfile.fromJson(input.toString(),input)}
        }
        val input=profile();input.getJSONObject("settings").getJSONObject("storage").put("type","local")
        assertThrows(IllegalArgumentException::class.java){S3BypassProfile.fromJson(input.toString(),input)}
    }
    @Test fun unknownRoutingIsNotSilentlyDiscarded(){
        val input=JSONObject().put("routing",JSONObject()).put("outbounds",org.json.JSONArray().put(JSONObject().put("protocol","fedarisha").put("settings",profile().getJSONObject("settings"))))
        assertThrows(IllegalArgumentException::class.java){S3BypassProfile.fromJson(input.toString(),input)}
    }
    @Test fun storageCredentialsAreRedacted(){
        val clean=ClientEvents.redact(profile().toString())
        assertFalse(clean.contains("fixture-access-value"));assertFalse(clean.contains("fixture-secret-value"))
    }
    @Test fun encodedConnectionLinkIsRedactedAndRecognizedByExplicitImportGate(){
        val input=profile();val link=AndroidDeployS3.link(input)
        assertEquals(link,ImportLink.config(link))
        assertEquals(S3BypassProfile.KIND,ServerImport.read(link).single().protocol)
        assertFalse(ClientEvents.redact("Подключение "+link).contains(link))
    }
    @Test fun awsSigningFieldsAreNotPublicDiagnostics(){
        val clean=ClientEvents.redact("Authorization: AWS4-HMAC-SHA256 Credential=fixture-access/20261017/region/s3/aws4_request, SignedHeaders=host, Signature=fixture-signature")
        assertFalse(clean.contains("fixture-access"));assertFalse(clean.contains("fixture-signature"))
    }
}
