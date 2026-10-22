package org.mcrf.client

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class SwgProfileTest {
    @Test fun automaticCountSurvivesNormalizationAndShareImport() {
        for(count in listOf(1,2,6,32)) {
            val raw=json("qwdtt").put("hashes",JSONArray()).put("hash_source","auto").put("workers",27).put("vk_call_count",count)
            val before=raw.toString();val profile=SwgProfile.fromJson("fixture",raw).carrier!!
            assertEquals(count,profile.getInt("vk_call_count"));assertEquals(before,raw.toString())
            val restored=ServerImport.read(SwgProfile.share(profile)).single().carrier!!
            assertEquals(count,restored.getInt("vk_call_count"));assertEquals("auto",restored.getString("hash_source"))
            assertEquals(0,restored.getJSONArray("hashes").length())
        }
    }
    @Test fun automaticWorkersRespectSelectedCountAndLegacyDefaults() {
        val raw=json("qwdtt").put("hashes",JSONArray()).put("hash_source","auto").put("vk_call_count",1).put("workers",54)
        assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",raw)}
        raw.put("workers",27);assertEquals(27,SwgProfile.fromJson("fixture",raw).carrier!!.getInt("workers"))
        raw.remove("vk_call_count");raw.put("workers",162)
        assertEquals(6,SwgProfile.fromJson("fixture",raw).carrier!!.getInt("vk_call_count"))
        for(count in listOf(0,-1))assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",JSONObject(raw.toString()).put("vk_call_count",count))}
        assertEquals(33,SwgProfile.fromJson("fixture",JSONObject(raw.toString()).put("vk_call_count",33)).carrier!!.getInt("vk_call_count"))
    }
    private fun enc(value: String) = URLEncoder.encode(value,"UTF-8").replace("+","%20")
    private val calls = (1..6).map { "fixture-call-${it}-0123456789abcdef" }
    private fun json(type: String) = JSONObject().put("type",type).put("server","fixture.invalid").put("server_port",if(type=="csqtt") 46000 else 56000)
        .put("password","secret +&:ёж").put("hashes",JSONArray(calls)).put("workers",18)
    private fun reject(block: () -> Unit) { try { block(); fail("must reject") } catch (_: Exception) { } }
    @Test fun sixCallsAreNotTruncatedAndShareRoundTrips() {
        for(type in SwgProfile.TYPES) {
            val data=json(type);val shared=SwgProfile.share(data);val imported=ServerImport.read(shared).single()
            assertEquals(type,imported.protocol);assertEquals(6,imported.carrier!!.getJSONArray("hashes").length())
            assertEquals("secret +&:ёж",imported.carrier.getString("password"))
            assertEquals(calls,(0 until 6).map { imported.carrier.getJSONArray("hashes").getString(it) })
        }
    }
    @Test fun panelFormatsAndLegacyWdttHaveRealPeers() {
        val cs="csqtt://connect?v=2&host=fixture.invalid&peer=46000&password=${enc("secret +&")}&hashes=${calls.joinToString("+")}&name=Test"
        val imported=ServerImport.read(cs).single();assertEquals("fixture.invalid",imported.host);assertEquals(46000,imported.port);assertEquals("secret +&",imported.carrier!!.getString("password"))
        val wd="wdtt://fixture.invalid:56000:56001:9000:${enc("secret +&")}:${calls.joinToString(",")}#Legacy"
        val legacy=ServerImport.read(wd).single();assertEquals("Legacy",legacy.name);assertEquals(6,legacy.carrier!!.getJSONArray("hashes").length());assertEquals(56001,legacy.carrier.getInt("wg_port"))
    }
    @Test fun qwdttAliasAndRawPortAreExplicit() {
        val link="qwdtt:config?peer=${enc("[2001:db8::1]:55111")}&pass=${enc("p+&")}&hashes=${calls.joinToString(",")}&mode=raw&raw_port=55113&workers=162"
        val entry=ServerImport.read(link).single();val config=SwgProfile.privateConfig(entry.carrier!!,22300,22301,"user","pass","installation")
        assertEquals("[2001:db8::1]:55113",config.getString("peer"));assertEquals(162,config.getInt("workers"))
        assertEquals("p+&",config.getString("password"))
        assertEquals("wg",ServerImport.read(SwgProfile.share(json("qwdtt"))).single().carrier!!.getString("mode"))
    }
    @Test fun normalizesUserCallLinksWithoutVkLogin() {
        val value=json("csqtt").put("hashes",JSONArray(calls.map { "https://vk.ru/call/join/$it?fixture=1" }))
        val profile=SwgProfile.fromJson(value.toString(),value).carrier!!
        assertEquals(calls,(0 until 6).map { profile.getJSONArray("hashes").getString(it) });assertEquals("manual",profile.getString("hash_source"))
    }
    @Test fun stableDeviceAndFreshListenerCredentials() {
        val profile=json("csqtt");val first=SwgProfile.privateConfig(profile,23400,23401,"one","first","installation")
        val second=SwgProfile.privateConfig(profile,24500,24501,"two","second","installation")
        assertEquals(first.getString("device_id"),second.getString("device_id"));assertNotEquals(first.getString("socks_pass"),second.getString("socks_pass"))
        profile.put("device_id","existing-binding");assertEquals("existing-binding",SwgProfile.privateConfig(profile,23400,23401,"one","first","installation").getString("device_id"))
    }
    @Test fun rejectsSevenCallsInvalidHostsMissingParametersAndInjection() {
        for(type in SwgProfile.TYPES) {
            listOf(json(type).put("hashes",JSONArray(calls+"fixture-call-7-0123456789abcdef")), json(type).put("server","bad/path"),
                json(type).put("password","secret|injection"), json(type).put("hashes",JSONArray()),json(type).put("workers",100),
                json(type).put("device_id","x\ncommand")).filterNot { type == "qwdtt" && it.getJSONArray("hashes").length() == 7 }.forEach { candidate -> reject { SwgProfile.fromJson(candidate.toString(),candidate) } }
        }
        reject { ServerImport.read("qwdtt://config?peer=fixture.invalid:56000&peer=other:1234&pass=secret&hashes=${calls[0]}") }
        reject { ServerImport.read("csqtt://connect?v=1&host=fixture.invalid&peer=46000&password=secret&hashes=${calls[0]}") }
        reject { SwgProfile.fromJson("fixture",json("csqtt").put("hashes",JSONArray().put("https://not-vk.invalid/call/join/${calls[0]}"))) }
    }
    @Test fun fullQwdttReservePoolPreservesNewlinesAndDoesNotMultiplyWorkers() {
        val pool = (1..24).map { "https://vk.ru/call/join/fixture-call-$it-0123456789abcdef" }
        val link = "qwdtt://config?name=test&peer=fixture.invalid%3A56000&pass=fixture&hashes=${enc(pool.joinToString("\n"))}&workers=18&port=9000"
        val parsed = ServerImport.read(link).single().carrier!!
        assertEquals(24, parsed.getJSONArray("hashes").length()); assertEquals(18, parsed.getInt("workers"))
        assertEquals(24, ServerImport.read(SwgProfile.share(parsed)).single().carrier!!.getJSONArray("hashes").length())
        assertEquals(65,SwgProfile.fromJson("fixture",json("qwdtt").put("hashes",JSONArray((1..65).map { "fixture-call-$it-0123456789abcdef" }))).carrier!!.getJSONArray("hashes").length())
    }
    @Test fun editorKeepsIdentityAndExportsCompatibleLink() {
        val entry=ServerImport.read(SwgProfile.share(json("csqtt"))).single()
        val changed=ConnectionEdits.apply(entry,ConnectionEdits.snapshot(entry).apply { put("name","Edited");getJSONObject("carrier").put("server","new-fixture.invalid") })
        assertEquals(entry.id,changed.id);assertEquals("new-fixture.invalid",changed.host)
        val shared=ServerImport.read(ConnectionEdits.share(changed,true)).single();assertEquals("Edited",shared.name);assertEquals(6,shared.carrier!!.getJSONArray("hashes").length())
    }
    @Test fun autoQwdttRoundTripsWithoutLeakingAccountOrInventingCalls() {
        val raw=json("qwdtt").put("hashes",JSONArray()).put("hash_source","auto").put("workers",162).put("token","never-export-me")
        val profile=SwgProfile.fromJson("fixture",raw).carrier!!
        assertEquals("auto",profile.getString("hash_source"));assertEquals(0,profile.getJSONArray("hashes").length())
        val shared=SwgProfile.share(profile);assertFalse(shared.contains("never-export-me"));assertFalse(shared.contains("token"))
        val restored=ServerImport.read(shared).single().carrier!!
        assertEquals("auto",restored.getString("hash_source"));assertEquals(162,restored.getInt("workers"))
        reject {SwgProfile.privateConfig(restored,23000,23001,"u","p","fixture")}
        for(kind in listOf("wdtt","csqtt"))reject {SwgProfile.fromJson("fixture",json(kind).put("hashes",JSONArray()).put("hash_source","auto"))}
    }
    @Test fun suppliedQwdttCallsOverrideAutoAndShareUsesOriginalNewlineCodec() {
        val profile=SwgProfile.fromJson("fixture",json("qwdtt").put("hash_source","auto")).carrier!!
        assertEquals("manual",profile.getString("hash_source"))
        val share=SwgProfile.share(profile)
        val query=java.net.URI(share).rawQuery.split('&').associate {val pair=it.split('=',limit=2);pair[0] to java.net.URLDecoder.decode(pair[1],"UTF-8")}
        assertEquals(calls,query.getValue("hashes").lines());assertFalse(query.containsKey("hash_source"))
    }
    @Test fun csqttShareIsCompatibleWithOriginalRawPlusParser() {
        val share=SwgProfile.share(json("csqtt"))
        val raw=java.net.URI(share).rawQuery.split('&').single{it.startsWith("hashes=")}.substringAfter('=')
        assertEquals(calls,raw.split('+').map{java.net.URLDecoder.decode(it,"UTF-8")})
        assertEquals(6,ServerImport.read(share).single().carrier!!.getJSONArray("hashes").length())
    }
    @Test fun legacyCsqttWithSuppliedCallsSupportsIpv6AndEscapedPassword() {
        val legacy="csqtt://${enc("secret +:ёж")}@[2001:db8::1]:46000?hashes=${calls.joinToString("+")}&name=Legacy"
        val parsed=ServerImport.read(legacy).single()
        assertEquals("2001:db8::1",parsed.host);assertEquals("secret +:ёж",parsed.carrier!!.getString("password"))
        assertEquals(6,parsed.carrier.getJSONArray("hashes").length())
        try {SwgProfile.link("csqtt://secret@fixture.invalid:46000");fail("Missing calls accepted")}
        catch(error:IllegalArgumentException){assertTrue(error.message.orEmpty().contains("не содержит звонки"))}
    }
}
