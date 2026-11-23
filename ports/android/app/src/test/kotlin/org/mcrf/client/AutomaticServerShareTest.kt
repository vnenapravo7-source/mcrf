package org.mcrf.client

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutomaticServerShareTest {
    private val source="vless://11111111-1111-4111-8111-111111111111@example.invalid:443?security=tls#Тест"
    private fun reject(block:()->Unit) { try {block();fail("Must reject")}catch(_:IllegalArgumentException){} }
    private fun pack(bytes:ByteArray)=CompatibilityImportEncrypted.AUTOMATIC_PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun bytes(link:String)=Base64.getUrlDecoder().decode(link.removePrefix(CompatibilityImportEncrypted.AUTOMATIC_PREFIX))

    @Test fun selfContainedLinkImportsWithoutPasswordAndNeverFetchesForLocalServer() {
        val link=CompatibilityImportEncrypted.encryptAutomatic(source)
        assertTrue(CompatibilityImportEncrypted.isEncrypted(link))
        assertTrue(CompatibilityImportEncrypted.isAutomatic(link))
        assertEquals(link,ImportLink.config(link))
        assertFalse(link.contains("example.invalid"))
        assertEquals(source,CompatibilityImportEncrypted.decryptAutomatic(link))
        assertEquals(source,ImportPlan.prepareEncrypted(link,charArrayOf()){throw AssertionError("No fetch")}.entries.single().original)
    }
    @Test fun eachShareHasIndependent256BitSecretAndNonce() {
        val first=bytes(CompatibilityImportEncrypted.encryptAutomatic(source))
        val second=bytes(CompatibilityImportEncrypted.encryptAutomatic(source))
        assertFalse(first.copyOfRange(8,40).contentEquals(second.copyOfRange(8,40)))
        assertFalse(first.copyOfRange(40,52).contentEquals(second.copyOfRange(40,52)))
        assertEquals("MCRFSH02",String(first.copyOfRange(0,8),Charsets.US_ASCII))
        val plain=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE,SecretKeySpec(first.copyOfRange(8,40),"AES"),GCMParameterSpec(128,first.copyOfRange(40,52)))
            updateAAD(first,0,52)
        }.doFinal(first,52,first.size-52)
        val document=JSONObject(String(plain,Charsets.UTF_8))
        assertEquals(2,document.getInt("version"));assertEquals("mcrf-share",document.getString("format"))
        assertEquals(source,document.getString("source"))
    }
    @Test fun damagedMagicSecretNonceCiphertextAndTagRejectBeforeImport() {
        val packed=bytes(CompatibilityImportEncrypted.encryptAutomatic(source))
        for(index in listOf(0,7,8,39,40,51,52,packed.lastIndex)) {
            val bad=packed.copyOf().also {it[index]=(it[index].toInt() xor 1).toByte()}
            reject {CompatibilityImportEncrypted.decryptAutomatic(pack(bad))}
        }
        for(size in listOf(0,7,40,52,67,packed.size-1))reject {CompatibilityImportEncrypted.decryptAutomatic(pack(packed.copyOf(size)))}
    }
    @Test fun unsupportedVersionsNestingOversizeAndNonCanonicalEncodingReject() {
        val link=CompatibilityImportEncrypted.encryptAutomatic(source)
        reject {CompatibilityImportEncrypted.decryptAutomatic(link.replace("/v2/","/v3/"))}
        reject {CompatibilityImportEncrypted.decrypt(link.replace("/v2/","/v3/"),charArrayOf())}
        reject {CompatibilityImportEncrypted.decryptAutomatic(link+"=")}
        reject {CompatibilityImportEncrypted.decryptAutomatic(link+"!")}
        reject {CompatibilityImportEncrypted.encryptAutomatic(link)}
        reject {CompatibilityImportEncrypted.encryptAutomatic("  $link\n")}
        reject {CompatibilityImportEncrypted.encryptAutomatic("x".repeat(CompatibilityImportEncrypted.MAX_SOURCE+1))}
        assertTrue(CompatibilityImportEncrypted.isEncrypted("mcrf://encrypted/v3/unsupported"))
    }
    @Test fun legacySeparatePasswordLinksRemainSupported() {
        val password="legacy-fixture-secret".toCharArray()
        val legacy=CompatibilityImportEncrypted.encrypt(source,password)
        assertFalse(CompatibilityImportEncrypted.isAutomatic(legacy))
        assertEquals(source,CompatibilityImportEncrypted.decrypt(legacy,password))
        reject {CompatibilityImportEncrypted.decrypt(legacy,"wrong-fixture-secret".toCharArray())}
    }
    @Test fun authenticatedButUnsupportedPayloadDoesNotReachImport() {
        val header=bytes(CompatibilityImportEncrypted.encryptAutomatic(source)).copyOf(52)
        fun wrap(document:JSONObject):String {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE,SecretKeySpec(header.copyOfRange(8,40),"AES"),GCMParameterSpec(128,header.copyOfRange(40,52)))
                updateAAD(header)
            }
            return pack(header+cipher.doFinal(document.toString().toByteArray(Charsets.UTF_8)))
        }
        fun document()=JSONObject().put("format","mcrf-share").put("version",2).put("source",source)
        for(bad in listOf(document().put("version",3),document().put("version","2"),
            document().put("format","other"),document().put("extra",true),document().put("source",17),
            document().put("source","mcrf://encrypted/v3/nested"),document().put("source","  mcrf://encrypted/v3/nested\n"))) {
            reject {CompatibilityImportEncrypted.decryptAutomatic(wrap(bad))}
        }
    }
}
