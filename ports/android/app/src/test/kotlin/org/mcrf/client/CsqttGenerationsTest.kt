package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class CsqttGenerationsTest {
    private fun profile()=JSONObject().put("type","csqtt").put("server","fixture.invalid").put("server_port",46000)
        .put("password","fixture-only").put("hashes",JSONArray().put(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){1})))
    private fun encrypted(query:String):String {
        val nonce=ByteArray(12){it.toByte()}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("csqtt-chiper-key".toByteArray()),"AES"),GCMParameterSpec(128,nonce))
        return "csqtt://"+Base64.getUrlEncoder().withoutPadding().encodeToString(nonce+cipher.doFinal(query.toByteArray()))
    }
    @Test fun allLegacyFamiliesHaveDistinctPinnedModulesAndOldDefaultIsUnchanged(){
        assertEquals("csqtt",EngineModules.carrierKind(profile()))
        CsqttGenerations.legacy.forEach {family->
            val normal=SwgProfile.fromJson("fixture",profile().put("csqtt_generation",family.id)).carrier!!
            assertEquals(family.engine,EngineModules.carrierKind(normal))
            assertEquals(family,CsqttGenerations.family(normal))
            assertTrue(family.revision.matches(Regex("[0-9a-f]{40}")))
        }
        assertEquals(4,CsqttGenerations.families.map {EngineModules.packageName(it.engine)}.distinct().size)
        assertThrows(IllegalArgumentException::class.java){EngineModules.carrierKind(profile().put("csqtt_generation","future"))}
        assertThrows(IllegalArgumentException::class.java){EngineModules.carrierKind(profile().put("csqtt_generation",2.0))}
    }
    @Test fun modernOriginalRebirthAndSwgKeepSameCompatibleEngineAndExplicitOrigin(){
        CsqttGenerations.modernImplementations.keys.forEach {origin->
            val normal=Csqtt25Profile.fromJson("fixture",profile().put("csqtt_generation","2.5").put("csqtt_implementation",origin)).carrier!!
            assertEquals(origin,normal.getString("csqtt_implementation"));assertEquals("csqtt25",EngineModules.carrierKind(normal))
        }
        assertThrows(IllegalArgumentException::class.java){Csqtt25Profile.fromJson("fixture",profile().put("csqtt_generation","2.5").put("csqtt_implementation","unknown"))}
    }
    @Test fun historicalNativeSetsCannotBeSubstitutedWithAnotherFamily(){
        for(kind in listOf("csqtt20","csqtt215")){
            val entries=EngineModules.libraries(kind).map {"lib/arm64-v8a/$it"}
            assertTrue(EngineModules.validNativeEntries(kind,"arm64-v8a",entries))
            for(other in CsqttGenerations.engines-kind)assertFalse(EngineModules.validNativeEntries(other,"arm64-v8a",entries))
        }
    }
    @Test fun historicalPrivateConfigKeepsGenerationAndCompleteCallPool(){
        val hashes=(1..12).map {index->Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){index.toByte()})}
        for(generation in listOf("2.0","2.1.5")){
            val raw=profile().put("csqtt_generation",generation).put("hashes",JSONArray(hashes+hashes))
            val normal=SwgProfile.fromJson("fixture",raw).carrier!!
            assertEquals(12,normal.getJSONArray("hashes").length())
            val private=SwgProfile.privateConfig(normal,22001,22002,"fixture-user","fixture-pass","fixture-installation")
            assertEquals(generation,private.getString("csqtt_generation"))
            assertEquals(12,private.getJSONArray("hashes").length())
            val shared=ServerImport.read(SwgProfile.share(normal)).single()
            assertEquals(EngineModules.carrierKind(normal),EngineModules.carrierKind(shared.carrier!!))
            assertEquals(36,CarrierStreamDefaults.apply(normal,JSONObject().put("csqtt",36)).getInt("workers"))
            assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",JSONObject(normal.toString()).put("vk_anon_path","auto_js").put("vk_hash_mode","auto_js"))}
        }
    }
    @Test fun historicalUdpOnlyClientRejectsTcpInsteadOfSilentlyIgnoringIt(){
        assertThrows(IllegalArgumentException::class.java){SwgProfile.fromJson("fixture",profile().put("csqtt_generation","2.0").put("turn_tcp",true))}
        assertTrue(SwgProfile.fromJson("fixture",profile().put("csqtt_generation","2.1.5").put("turn_tcp",true)).carrier!!.getBoolean("turn_tcp"))
    }
    @Test fun explicitImportChoiceDoesNotCoerceModernIntoLegacyOrRewriteOtherProtocols(){
        val old=profile().toString();assertEquals(old,CsqttGenerations.applyImportChoice(old,""))
        val historical=ServerImport.read(CsqttGenerations.applyImportChoice(old,"2.0")).single()
        assertEquals("csqtt20",EngineModules.carrierKind(historical.carrier!!))
        val modern=profile().put("csqtt_generation","2.5").toString()
        assertThrows(IllegalArgumentException::class.java){CsqttGenerations.applyImportChoice(modern,"2.0")}
        assertThrows(IllegalArgumentException::class.java){CsqttGenerations.applyImportChoice(old,"rebirth-2.5.0")}
        assertThrows(IllegalArgumentException::class.java){CsqttGenerations.applyImportChoice("https://fixture.invalid/subscription","2.0")}
        val mixed=JSONArray().put(old).put("socks5://fixture.invalid:1080").toString()
        assertEquals("socks5://fixture.invalid:1080",ServerImport.read(CsqttGenerations.applyImportChoice(mixed,"2.1.5"))[1].original)
    }
    @Test fun userinfoGenerationMustBeExplicitValidAndLegacy(){
        val link="csqtt://fixture-only@fixture.invalid:46000?hashes=fixture-call-0123456789abcdef&csqtt_generation=2.0"
        assertEquals("csqtt20",EngineModules.carrierKind(SwgProfile.fromJson("fixture",CompatibilityImportCsqttLegacy.raw(link)).carrier!!))
        assertThrows(IllegalArgumentException::class.java){CompatibilityImportCsqttLegacy.raw(link.replace("=2.0","=2.5"))}
    }
    @Test fun encryptedUpstreamConcatenatedAndDelimitedQueriesPreservePlusAndMoreThanSixCalls(){
        val calls=(1..12).map {"fixture-call-0123456789-$it%2Btail"}.joinToString("+")
        for(separator in listOf("&",";","&amp;","")){
            val query=listOf("v=2","host=fixture.invalid","peer=46000","password=fixture-only","workers=20","hashes=$calls").joinToString(separator)
            val entry=Csqtt25Profile.encryptedLink(encrypted(query))
            assertEquals("csqtt25",EngineModules.carrierKind(entry.carrier!!))
            assertEquals(12,entry.carrier!!.getJSONArray("hashes").length())
            assertEquals("fixture-call-0123456789-1+tail",entry.carrier!!.getJSONArray("hashes").getString(0))
        }
    }
    @Test fun malformedOrDuplicateQueryCannotRecoverByDroppingFields(){
        val base="v=2&host=fixture.invalid&peer=46000&password=fixture-only&hashes=fixture-call-0123456789abcdef"
        for(query in listOf("$base&host=other.invalid","$base&unknown=required","$base&","junkv=2host=fixture.invalidpeer=46000password=x","v=2host=fixture.invalidpeer=46000password=xhost=other.invalid")){
            assertThrows(IllegalArgumentException::class.java){CsqttLinkQuery.parse(query)}
        }
    }
}
