package org.mcrf.client

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Independently implemented 2.5 link envelope, audited against supplied APK b2049cb1.
 * CSQTT required notice: Copyright 2026 amurcanov. PolyForm Noncommercial 1.0.0.
 * Decoding a link does NOT make the incompatible 2.5 transport available.
 * Never forward the result into the legacy CSQTT runtime without an explicit version gate.
 */
object Csqtt25Envelope {
    class Decoded internal constructor(val link:String) {
        override fun toString()="Csqtt25Envelope(redacted)"
    }
    fun decode(source:String):Decoded {
        require(source.length<=65536) {"Ссылка CSQTT 2.5 слишком длинная."}
        try {
            val text=source.trim()
            require(text.startsWith("csqtt://",ignoreCase=true))
            val encoded=text.substring(8).filterNot {it==' '||it=='\r'||it=='\n'}
            require(encoded.matches(Regex("[A-Za-z0-9+/_-]+={0,2}")))
            val bytes=try {Base64.getUrlDecoder().decode(encoded)}catch(_:IllegalArgumentException){Base64.getDecoder().decode(encoded)}
            require(bytes.size>=28)
            // This is the public format key used by upstream, not an account secret.
            val key=MessageDigest.getInstance("SHA-256").digest("csqtt-chiper-key".toByteArray(Charsets.UTF_8))
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,bytes,0,12))
            val plain=cipher.doFinal(bytes,12,bytes.size-12)
            val decoded=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString().trim()
            require(decoded.isNotEmpty()&&decoded.none(Char::isISOControl))
            val link=when {
                decoded.startsWith("csqtt://connect?",true)->decoded
                decoded.startsWith("connect?",true)->"csqtt://$decoded"
                decoded.startsWith("csqtt://",true)->"csqtt://connect?"+decoded.substring(8)
                else->"csqtt://connect?$decoded"
            }
            val uri=URI(link)
            require(uri.scheme.equals("csqtt",true)&&uri.host.equals("connect",true)&&uri.rawUserInfo==null&&
                uri.port==-1&&uri.rawPath.isNullOrEmpty()&&uri.rawFragment==null&&!uri.rawQuery.isNullOrBlank())
            return Decoded(link)
        }catch(_:Exception){
            // No plaintext fallback after authentication failure, no link/password in diagnostics.
            throw IllegalArgumentException("Не удалось проверить зашифрованную ссылку CSQTT 2.5.")
        }
    }
}
