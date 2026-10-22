package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Isolated P0 test: no unfinished P2 SteerProbePlan/dispatcher dependency. */
class SteerLinkEdits49Test {
    private val id="4b72af9e-32b4-4b14-a2fc-4a9100d2eb21"
    private fun output(server:ServerImport.Server)=JSONObject(server.outbound!!.toString()).apply {remove("tag")}
    private fun edited(base:ServerImport.Server,mutate:(JSONObject)->Unit):ServerImport.Server {
        val snapshot=ConnectionEdits.snapshot(base);mutate(snapshot.getJSONObject("outbound"));return ConnectionEdits.apply(base,snapshot)
    }
    private fun check(entry:ServerImport.Server):String {
        val original=ConnectionEdits.snapshot(entry).toString();val stableId=entry.id
        val profile=SteerEffectiveProfile.profile(entry);val parsed=ServerImport.read(profile).single()
        assertEquals(entry.host,parsed.host);assertEquals(entry.port,parsed.port)
        assertTrue(RoutingDefaultsEdits.equivalent(output(entry),output(parsed)))
        assertEquals(stableId,entry.id);assertEquals(original,ConnectionEdits.snapshot(entry).toString());return profile
    }
    @Test fun everyNamedFingerprintAndTransportSurvivesEffectiveEdits() {
        for(fp in listOf("chrome","edge","firefox","safari","ios","qq","360","android","random","randomized"))
            for(type in listOf("tcp","ws","httpupgrade","grpc","xhttp")) {
                val base=ServerImport.read("vless://$id@one.example.test:443?security=reality&sni=one.example.test&pbk=CQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abcd&fingerprint=$fp&type=$type&owned_extra=keep").single()
                val changed=edited(base){it.put("server_port",8443);it.getJSONObject("tls").getJSONObject("utls").put("fingerprint",fp)}
                val result=check(changed);assertTrue(result.contains("owned_extra=keep"))
                assertFalse(result.contains("fingerprint="));assertTrue(result.contains("fp="))
            }
    }
    @Test fun vmessEditsDoNotKeepOldTlsAliasesOrAddAbsentDefaults() {
        for(explicit in listOf(false,true)) {
            val fields=JSONObject().put("add","one.example.test").put("port",443).put("id",id).put("net","ws").put("tls","tls")
                .put("sni","one.example.test").put("fp","chrome").put("alpn","h2,http/1.1")
            if(explicit)fields.put("allowInsecure",true)
            val base=ServerImport.read("vmess://"+java.util.Base64.getEncoder().encodeToString(fields.toString().toByteArray(Charsets.UTF_8))).single()
            val changed=edited(base){it.put("server_port",8443);it.getJSONObject("tls").apply {
                getJSONObject("utls").put("fingerprint","edge");put("alpn",org.json.JSONArray().put("http/1.1"))
                if(explicit)put("insecure",false)
            }}
            val result=check(changed);val decoded=JSONObject(CompatibilityImportFormats.decodeBase64(result.substringAfter("://")))
            assertEquals("edge",decoded.getString("fp"));assertEquals("http/1.1",decoded.getString("alpn"));assertFalse(decoded.has("allowInsecure"))
            assertEquals(explicit,decoded.has("insecure"))
        }
    }
    @Test fun unrepresentableFieldsNeverFallBackToTheOldLink() {
        val base=ServerImport.read("vless://$id@one.example.test:443?security=tls&sni=one.example.test").single()
        val changed=edited(base){it.put("detour","other")}
        assertThrows(IllegalArgumentException::class.java){SteerEffectiveProfile.profile(changed)}
    }
    @Test fun representableJsonIsConvertedEvenWithoutAnEdit() {
        val links=listOf(
            "vless://$id@one.example.test:443?security=tls&sni=one.example.test&fp=edge&type=ws&path=%2Fowned",
            "trojan://owned-fixture@one.example.test:443?security=tls&sni=one.example.test&fp=firefox",
            "socks://owned-user:owned-password@one.example.test:1080"
        )
        for(link in links){
            val base=ServerImport.read(link).single()
            val json=ServerImport.read(base.outbound!!.toString()).single()
            val result=check(json)
            assertTrue(result.contains("://"));assertFalse(result.startsWith("{"))
        }
    }
    @Test fun portEditDoesNotInventRealitySni() {
        for(sni in listOf("","&sni=")){
            val base=ServerImport.read("vless://$id@one.example.test:443?security=reality&pbk=CQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abcd$sni").single()
            val changed=edited(base){it.put("server_port",8443)}
            val result=check(changed)
            assertEquals("",ServerImport.read(result).single().outbound!!.getJSONObject("tls").getString("server_name"))
        }
    }
    @Test fun vmessEffectiveEditRemovesBothOldAliases() {
        val source=JSONObject().put("add","one.example.test").put("port",443).put("id",id).put("tls","TLS")
            .put("serverName","one.example.test").put("fingerprint","chrome").put("alpn",org.json.JSONArray().put("http/1.1"))
        val base=ServerImport.read("vmess://"+java.util.Base64.getEncoder().encodeToString(source.toString().toByteArray(Charsets.UTF_8))).single()
        val changed=edited(base){it.put("server_port",8443);it.getJSONObject("tls").getJSONObject("utls").put("fingerprint","edge")}
        val profile=check(changed)
        val raw=JSONObject(CompatibilityImportFormats.decodeBase64(profile.substringAfter("://")))
        assertFalse(raw.has("fingerprint"));assertFalse(raw.has("serverName"));assertEquals("edge",raw.getString("fp"))
    }
}
