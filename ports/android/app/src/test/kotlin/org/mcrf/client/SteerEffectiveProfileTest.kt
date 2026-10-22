package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Queued whole-P2 source cases. No actual protocol/TLS acceptance implied. */
class SteerEffectiveProfileTest {
    private val uuid="4b72af9e-32b4-4b14-a2fc-4a9100d2eb21"
    private fun edited(server:ServerImport.Server,work:(JSONObject)->Unit)=ConnectionEdits.apply(server,ConnectionEdits.snapshot(server).apply {work(getJSONObject("outbound"))})
    private fun roundtrip(server:ServerImport.Server):String {
        val before=ConnectionEdits.snapshot(server).toString();val id=server.id
        val value=SteerProbePlan.profile(server);val parsed=ServerImport.read(value).single()
        fun output(item:ServerImport.Server)=JSONObject(item.outbound!!.toString()).apply {remove("tag")}
        assertTrue(RoutingDefaultsEdits.equivalent(output(server),output(parsed)))
        assertEquals(server.host,parsed.host);assertEquals(server.port,parsed.port)
        assertEquals(id,server.id);assertEquals(before,ConnectionEdits.snapshot(server).toString())
        return value
    }
    @Test fun unchangedOrNameOnlyEditsKeepTheExactOriginalNativeOptions() {
        val server=ServerImport.read("vless://$uuid@one.example.test:443?security=tls&sni=one.example.test&steer_extra=keep#owned").single()
        assertEquals(server.original,roundtrip(server))
        val rename=ConnectionEdits.apply(server,ConnectionEdits.snapshot(server).put("name","renamed"))
        assertEquals(server.original,roundtrip(rename))
    }
    @Test fun vlessIpv6HostPortTlsWsAndUnknownOriginalOptionsSurvive() {
        val base=ServerImport.read("vless://$uuid@one.example.test:443?security=tls&sni=one.example.test&type=ws&path=%2Fws&host=cdn.example.test&steer_extra=keep#owned").single()
        val changed=edited(base){node->node.put("server","2001:db8::7").put("server_port",8443)
            node.getJSONObject("tls").put("server_name","different.example.test")
            node.getJSONObject("transport").put("path","/a b?x=+")}
        val value=roundtrip(changed)
        assertNotEquals(base.original,value);assertTrue(value.contains("[2001:db8::7]:8443"));assertTrue(value.contains("steer_extra=keep"))
    }
    @Test fun passwordsAndBasicProtocolsUseEffectiveValuesWithoutLosingReservedCharacters() {
        val links=listOf("trojan://owned@one.example.test:443?sni=one.example.test",
            "hysteria2://owned@one.example.test:443?sni=one.example.test&obfs=salamander&obfs-password=own",
            "socks://owned:pass@one.example.test:1080","http://owned:pass@one.example.test:8080",
            "ss://YWVzLTEyOC1nY206b3duZWQ@one.example.test:443")
        links.forEach {link->val base=ServerImport.read(link).single()
            val changed=edited(base){it.put("server","two.example.test").put("password","pa:ss@=+/ value")}
            assertNotEquals(base.original,roundtrip(changed))
        }
    }
    @Test fun vmessEffectiveJsonRoundtripsWithOriginalUnknownOptionsIntact() {
        val fields=JSONObject().put("v","2").put("ps","owned").put("add","one.example.test").put("port",443)
            .put("id",uuid).put("aid",0).put("scy","auto").put("tls","tls").put("sni","one.example.test")
            .put("net","ws").put("path","/ws").put("host","cdn.example.test").put("steer_extra","keep")
        val base=ServerImport.read("vmess://"+java.util.Base64.getEncoder().encodeToString(fields.toString().toByteArray(Charsets.UTF_8))).single()
        val changed=edited(base){it.put("server","two.example.test").put("security","aes-128-gcm")}
        val result=roundtrip(changed)
        val decoded=JSONObject(CompatibilityImportFormats.decodeBase64(result.substringAfter("://")))
        assertEquals("keep",decoded.getString("steer_extra"))
    }
    @Test fun unrepresentableTlsOrTransportFieldsFailClosedInsteadOfDroppingEdits() {
        val base=ServerImport.read("vless://$uuid@one.example.test:443?security=tls&sni=one.example.test").single()
        val changed=edited(base){it.getJSONObject("tls").put("certificate_public_key_sha256",org.json.JSONArray().put("owned-test-pin"))}
        assertThrows(IllegalArgumentException::class.java){SteerProbePlan.profile(changed)}
        val detour=edited(base){it.put("detour","other-server")}
        assertThrows(IllegalArgumentException::class.java){SteerProbePlan.profile(detour)}
    }
    @Test fun vmessTlsEditsReplaceOldFingerprintAlpnAndInsecureAliases() {
        val source=JSONObject().put("add","one.example.test").put("port",443).put("id",uuid)
            .put("net","ws").put("tls","tls").put("sni","one.example.test").put("fp","chrome")
            .put("alpn","h2,http/1.1").put("allowInsecure",true)
        val base=ServerImport.read("vmess://"+java.util.Base64.getEncoder().encodeToString(source.toString().toByteArray(Charsets.UTF_8))).single()
        val changed=edited(base){node->node.getJSONObject("tls").apply {
            getJSONObject("utls").put("fingerprint","edge");put("alpn",org.json.JSONArray().put("http/1.1"));put("insecure",false)
        }}
        val result=roundtrip(changed)
        val decoded=JSONObject(CompatibilityImportFormats.decodeBase64(result.substringAfter("://")))
        assertEquals("edge",decoded.getString("fp"));assertEquals("http/1.1",decoded.getString("alpn"))
        assertFalse(decoded.has("allowInsecure"));assertFalse(decoded.getBoolean("insecure"))
    }
    @Test fun requestSeparatesStableCatalogueIdFromChangedNativeProfileAndRefusesTampering() {
        val base=ServerImport.read("vless://$uuid@one.example.test:443?security=tls&sni=one.example.test").single()
        val changed=edited(base){it.put("server","two.example.test")};val request=SteerProbePlan.request(changed)
        assertEquals(base.id,request.getString("id"));assertNotEquals(base.id,request.getString("profileSha256"))
        assertEquals("two.example.test",SteerProbePlan.captured(request).host)
        for(field in listOf("profileSha256","host","id"))assertThrows(IllegalArgumentException::class.java){SteerProbePlan.captured(JSONObject(request.toString()).put(field,"tampered"))}
        assertThrows(IllegalArgumentException::class.java){SteerProbePlan.captured(JSONObject(request.toString()).put("insecure",true))}
    }
}
