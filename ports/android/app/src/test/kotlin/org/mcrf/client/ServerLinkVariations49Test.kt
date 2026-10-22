package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Source package #49; execution waits for the whole compatibility batch. */
class ServerLinkVariations49Test {
    private val id = "4b72af9e-32b4-4b14-a2fc-4a9100d2eb21"
    private fun read(query: String) = ServerImport.read("vless://$id@fixture.example.test:443?$query").single()

    @Test fun emptyAndAbsentRealitySniNeverBecomeTheEndpointHostname() {
        for(suffix in listOf("","&sni=","&serverName=")) {
            val node=read("security=reality&pbk=CQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abcd$suffix").outbound!!
            assertEquals("",node.getJSONObject("tls").getString("server_name"))
        }
        assertEquals("fixture.example.test",read("security=tls").outbound!!.getJSONObject("tls").getString("server_name"))
    }
    @Test fun explicitEmptyAlpnIsNotAnEmptyProtocolToken() {
        assertFalse(read("security=tls").outbound!!.getJSONObject("tls").has("alpn"))
        assertEquals(0,read("security=tls&alpn=").outbound!!.getJSONObject("tls").getJSONArray("alpn").length())
        assertEquals("h2",read("security=tls&alpn=http%2F1.1%2C%20h2").outbound!!.getJSONObject("tls").getJSONArray("alpn").getString(1))
        for(value in listOf("h2%2C","%2Ch2","h2%00"))
            assertThrows(IllegalArgumentException::class.java){read("security=tls&alpn=$value")}
    }

    @Test fun transportAliasesDoNotChangeTheProtocol() {
        for ((alias, expected) in mapOf("tcp" to "tcp", "raw" to "tcp", "ws" to "ws", "websocket" to "ws",
            "grpc" to "grpc", "httpupgrade" to "httpupgrade", "xhttp" to "xhttp", "splithttp" to "xhttp")) {
            for (field in listOf("type", "network")) {
                val node = read("$field=$alias").outbound!!
                assertEquals(expected, node.optJSONObject("transport")?.optString("type") ?: "tcp")
            }
        }
        assertNull(read("type=raw&network=tcp").outbound!!.optJSONObject("transport"))
        assertThrows(IllegalArgumentException::class.java) { read("type=ws&network=grpc") }
    }

    @Test fun fingerprintsAndExplicitAlpnRemainExactAcrossTlsAndReality() {
        val names = listOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random",
            "randomized", "randomizednoalpn", "hellochrome_133", "hellofirefox_148", "helloedge_85", "custom")
        for (security in listOf("tls", "reality")) for (fp in names) for (field in listOf("fp", "fingerprint")) {
            val extra = if (security == "reality") "&pbk=CQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abcd" else ""
            val tls = read("security=$security&$field=$fp&alpn=http%2F1.1%2Ch2$extra").outbound!!.getJSONObject("tls")
            assertEquals(fp, tls.getJSONObject("utls").getString("fingerprint"))
            assertEquals("http/1.1", tls.getJSONArray("alpn").getString(0))
            assertEquals("h2", tls.getJSONArray("alpn").getString(1))
        }
        // Import preservation is NOT a promise that each engine accepts each combination.
        assertThrows(IllegalArgumentException::class.java) { read("security=tls&fp=edge&fingerprint=chrome") }
        assertThrows(IllegalArgumentException::class.java) { read("security=tls&fp=edge&fp=chrome") }
        assertThrows(IllegalArgumentException::class.java) { read("security=tls&sni=one.test&serverName=two.test") }
        assertTrue(read("security=tls&insecure=true&allowInsecure=1").outbound!!.getJSONObject("tls").getBoolean("insecure"))
        assertThrows(IllegalArgumentException::class.java) { read("security=tls&insecure=false&allowInsecure=1") }
    }

    @Test fun vmessDoesNotDropFingerprintAlpnOrExplicitCertificatePolicy() {
        val source = JSONObject().put("add", "fixture.example.test").put("port", 443).put("id", id)
            .put("net", "websocket").put("path", "/owned").put("tls", "tls").put("fp", "edge")
            .put("alpn", "http/1.1,h2").put("allowInsecure", true)
        val link = "vmess://" + java.util.Base64.getEncoder().encodeToString(source.toString().toByteArray(Charsets.UTF_8))
        val node = ServerImport.read(link).single().outbound!!
        assertEquals("ws", node.getJSONObject("transport").getString("type"))
        val tls = node.getJSONObject("tls")
        assertEquals("edge", tls.getJSONObject("utls").getString("fingerprint"))
        assertEquals(2, tls.getJSONArray("alpn").length())
        assertTrue(tls.getBoolean("insecure"))
    }
    @Test fun vmessAlpnArrayAndStringHaveTheSameMeaning() {
        val raw=JSONObject().put("add","fixture.example.test").put("port",443).put("id",id).put("tls","tls").put("fp","edge")
        for(value in listOf<Any>("http/1.1,h2",org.json.JSONArray().put("http/1.1").put("h2"))){
            raw.put("alpn",value)
            val link="vmess://"+java.util.Base64.getEncoder().encodeToString(raw.toString().toByteArray(Charsets.UTF_8))
            val alpn=ServerImport.read(link).single().outbound!!.getJSONObject("tls").getJSONArray("alpn")
            assertEquals("http/1.1",alpn.getString(0));assertEquals("h2",alpn.getString(1))
        }
    }
    @Test fun explicitServerDnsSurvivesOrdinaryProfileConstruction() {
        val resolver=JSONObject().put("server","owned-resolver").put("strategy","prefer_ipv6")
        val raw=JSONObject().put("type","vless").put("server","fixture.example.test").put("server_port",443)
            .put("uuid",id).put("domain_resolver",resolver)
        val entry=ServerImport.read(raw.toString()).single()
        val output=ServerImport.build(listOf(entry),entry.id,false,60,50,"Системный").getJSONArray("outbounds").getJSONObject(1)
        assertEquals("owned-resolver",output.getJSONObject("domain_resolver").getString("server"))
        assertEquals("prefer_ipv6",output.getJSONObject("domain_resolver").getString("strategy"))
    }
}
