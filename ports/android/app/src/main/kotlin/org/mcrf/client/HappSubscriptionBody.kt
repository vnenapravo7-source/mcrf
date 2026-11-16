package org.mcrf.client

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Official encrypt-tag HTTP contract only; URL key ID is never interpreted as key material. */
object HappSubscriptionBody {
    fun interface Keys { fun keyFor(origin: URI, id: String): ByteArray? }
    private val none = Keys { _, _ -> null }
    /** Explicitly scoped public documentation test key; never a production-key guess. */
    fun publicTestKeysFor(origin: URI): Keys = Keys { current, id ->
        if (id == "key02" && current.scheme == origin.scheme && current.host.equals(origin.host, true) && current.port == origin.port)
            "key02:+]%4ij#P\"/".toByteArray(Charsets.US_ASCII) else null
    }
    @JvmOverloads fun decode(body: ByteArray, encryptTag: String?, responseUrl: URI, keys: Keys = none): ByteArray {
        require(body.size <= ServerImport.MAX_BYTES) { "Подписка больше 4 МБ." }
        if (encryptTag == null) return body.copyOf()
        try {
            require(responseUrl.scheme.equals("https", true) && responseUrl.host != null && responseUrl.userInfo == null)
            val ids = responseUrl.rawQuery.orEmpty().split('&').mapNotNull { field ->
                val pair = field.split('=', limit = 2)
                if (URLDecoder.decode(pair[0].replace("+", "%2B"), "UTF-8") == "key") {
                    require(pair.size == 2); URLDecoder.decode(pair[1].replace("+", "%2B"), "UTF-8")
                } else null
            }
            require(ids.size == 1 && ids.single().length in 1..256 && ids.single().none(Char::isISOControl))
            val key = keys.keyFor(responseUrl, ids.single())?.copyOf() ?: throw IllegalArgumentException()
            try {
                require(key.size == 16 && encryptTag.length <= 64 && encryptTag.isNotBlank())
                val tag = Base64.getDecoder().decode(encryptTag.trim()); require(tag.size == 16)
                require(body.all { it.toInt() in 0..127 })
                val text = String(body, Charsets.US_ASCII).filterNot(Char::isWhitespace)
                val ciphertext = Base64.getDecoder().decode(text)
                return Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, "kkkkkkkkkkkk".toByteArray(Charsets.US_ASCII)))
                }.doFinal(ciphertext + tag).also { require(it.size <= ServerImport.MAX_BYTES) }
            } finally { key.fill(0) }
        } catch (_: Exception) { throw IllegalArgumentException("Шифрованное содержимое Happ не проверено: неизвестный ключ или повреждённые данные. Старые подключения сохранены.") }
    }
}
