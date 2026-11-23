package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class November8RequestTest {
    @Test fun portableBackupRoundTripsWithoutPasswordAndRejectsDamage() {
        val plain="{\"version\":1,\"stores\":{}}".toByteArray()
        val packed=BackupCodec.pack(plain)
        assertTrue(BackupCodec.isPortable(packed));BackupCodec.validateEnvelope(packed)
        assertArrayEquals(plain,BackupCodec.unpack(packed))
        packed[packed.lastIndex]=(packed.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java){BackupCodec.unpack(packed)}
        assertThrows(IllegalArgumentException::class.java){BackupCodec.unpack(byteArrayOf())}
    }
    @Test fun nativeCsqttUriPreservesImportedUriAndExportsJsonAsUri() {
        val json=JSONObject().put("type","csqtt").put("name","Fixture")
            .put("server","192.0.2.1").put("server_port",12345).put("password","owned-fixture")
            .put("hashes",org.json.JSONArray().put("A".repeat(43)).put("B".repeat(43)))
            .put("workers",20).put("csqtt_generation","2.5").put("csqtt_implementation","swg-panel-2.5.3")
        fun entry(original:String,carrier:JSONObject=json)=ServerImport.Server(original,"Fixture","csqtt","192.0.2.1",12345,null,null,null,carrier)
        val uri=OriginalCsqttShare.source(entry(json.toString()))
        assertTrue(uri.startsWith("csqtt://connect?v=2&"));assertFalse(uri.startsWith("{"))
        assertTrue(uri.contains("hashes="+"A".repeat(43)+"+"+"B".repeat(43)))
        assertEquals(uri,OriginalCsqttShare.source(entry(uri)))
        val parsed=ServerImport.read(uri).single().carrier!!
        assertEquals("192.0.2.1",parsed.getString("server"));assertEquals(12345,parsed.getInt("server_port"))
        assertEquals(2,parsed.getJSONArray("hashes").length())
        val legacy=JSONObject(json.toString()).put("csqtt_generation","legacy").put("workers",18).put("hashes",org.json.JSONArray().put("A".repeat(43)))
        legacy.remove("csqtt_implementation")
        val legacyEntry=entry(legacy.toString(),legacy)
        val legacyParsed=ServerImport.read(OriginalCsqttShare.source(legacyEntry)).single().carrier!!
        assertEquals(legacy.getJSONArray("hashes").getString(0),legacyParsed.getJSONArray("hashes").getString(0))
        val auto=JSONObject(json.toString()).put("hashes",org.json.JSONArray()).put("vk_token","owned-fixture-token").put("vk_hash_mode","auto_js").put("vk_auth_mode","auto_js")
        val autoUri=OriginalCsqttShare.source(entry(auto.toString(),auto))
        assertTrue(autoUri.contains("token="));assertFalse(autoUri.contains("hashes="))
        assertEquals("owned-fixture-token",ServerImport.read(autoUri).single().carrier!!.getString("vk_token"))
    }
    @Test fun visibleStylesAndExitChoicesMatchRequest() {
        assertEquals(8,ConnectionAppearance.choices.size)
        assertTrue(ConnectionAppearance.choices.keys.containsAll(listOf(ConnectionAppearance.DOT_MATRIX,ConnectionAppearance.ARC_REACTOR,ConnectionAppearance.ECLIPSE)))
        assertEquals("MCRF",ConnectionAppearance.choices[ConnectionAppearance.BLUEPRINT])
        assertFalse(ConnectionAppearance.choices.containsKey(ConnectionAppearance.WELDING))
        assertFalse(ConnectionAppearance.exits.containsKey(ConnectionAppearance.DOOR))
        assertEquals(3,ConnectionAppearance.exits.size)
        assertTrue(ConnectionAppearance.tabs(true,true,true).contains("Настройки"))
        assertEquals(15,WallpaperCombinations.all.count {it.id.startsWith("it-")})
        assertEquals(3,WallpaperCombinations.all.count {it.id.startsWith("cat-")})
    }
}
