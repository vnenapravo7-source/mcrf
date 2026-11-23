package org.mcrf.client

import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** MCRF-only share. V1 uses a separate password; V2 is a bearer link containing its random key.
 * V2 authenticates the entire header, but possession of the complete link grants access. */
object CompatibilityImportEncrypted {
    const val PREFIX = "mcrf://encrypted/v1/"
    const val AUTOMATIC_PREFIX = "mcrf://encrypted/v2/"
    private val magic = "MCRFSH02".toByteArray(Charsets.US_ASCII)
    private const val HEADER_SIZE = 8 + 32 + 12
    const val MAX_SOURCE = ServerImport.MAX_BYTES / 2
    fun isEncrypted(value: String) = value.startsWith("mcrf://encrypted/")
    fun isAutomatic(value: String) = value.startsWith(AUTOMATIC_PREFIX)
    fun encryptAutomatic(source: String): String {
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        try {
            require(sourceBytes.size <= MAX_SOURCE && !isEncrypted(source.trim())) { "Некорректный размер или вложенная шифрованная ссылка." }
        } finally { sourceBytes.fill(0) }
        val key = ByteArray(32)
        val nonce = ByteArray(12)
        val random = SecureRandom()
        random.nextBytes(key); random.nextBytes(nonce)
        val header = magic + key + nonce
        val plain = JSONObject().put("format", "mcrf-share").put("version", 2).put("source", source).toString().toByteArray(Charsets.UTF_8)
        var packed: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            val envelope = header + cipher.doFinal(plain)
            packed = envelope
            (AUTOMATIC_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope)).also {
                require(it.length <= ServerImport.MAX_BYTES * 2) { "Профиль слишком большой для шифрованной ссылки." }
            }
        } finally { key.fill(0); nonce.fill(0); header.fill(0); plain.fill(0); packed?.fill(0) }
    }
    fun decryptAutomatic(link: String): String {
        require(isAutomatic(link) && link.length <= ServerImport.MAX_BYTES * 2) { "Неизвестная версия шифрованной ссылки MCRF." }
        var packed: ByteArray? = null
        var key: ByteArray? = null
        var plain: ByteArray? = null
        try {
            val encoded = link.substring(AUTOMATIC_PREFIX.length)
            require(encoded.matches(Regex("[A-Za-z0-9_-]+")))
            val envelope = Base64.getUrlDecoder().decode(encoded)
            packed = envelope
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(envelope) == encoded)
            require(envelope.size in (HEADER_SIZE + 16)..(MAX_SOURCE * 6 + 128))
            require(envelope.copyOfRange(0, 8).contentEquals(magic))
            val secret = envelope.copyOfRange(8, 40)
            key = secret
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, envelope.copyOfRange(40, HEADER_SIZE)))
            cipher.updateAAD(envelope, 0, HEADER_SIZE)
            val decoded = cipher.doFinal(envelope, HEADER_SIZE, envelope.size - HEADER_SIZE)
            plain = decoded
            val data = AndroidBackup.decodeDocument(decoded)
            require(data.keys().asSequence().toSet() == setOf("format", "version", "source"))
            require(data.getString("format") == "mcrf-share" && data.get("version") == 2 && data.get("source") is String)
            return data.getString("source").also {
                require(it.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE && !isEncrypted(it.trim()))
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("Шифрованная ссылка MCRF повреждена или имеет неизвестную версию. Данные не изменены.")
        } finally { packed?.fill(0); key?.fill(0); plain?.fill(0) }
    }
    fun encrypt(source: String, password: CharArray): String {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE) { "Шифрованный обмен ограничен профилем до 2 МБ." }
        require(!isEncrypted(source)) { "Нельзя повторно шифровать шифрованную ссылку." }
        val bytes = JSONObject().put("format", "mcrf-share").put("version", 1).put("source", source).toString().toByteArray(Charsets.UTF_8)
        return try { PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(BackupCodec.encrypt(bytes, password)) } finally { bytes.fill(0) }
    }
    fun decrypt(link: String, password: CharArray): String {
        if (isAutomatic(link)) return decryptAutomatic(link)
        require(link.startsWith(PREFIX) && link.length <= ServerImport.MAX_BYTES * 2) { "Некорректная шифрованная ссылка MCRF." }
        try {
            val packed = Base64.getUrlDecoder().decode(link.substring(PREFIX.length))
            val plain = BackupCodec.decrypt(packed, password)
            return try {
                val data = AndroidBackup.decodeDocument(plain)
                require(data.getString("format") == "mcrf-share" && data.getInt("version") == 1)
                data.getString("source").also { require(it.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES && !isEncrypted(it)) }
            } finally { plain.fill(0) }
        } catch (_: Exception) { throw IllegalArgumentException("Неверный пароль или шифрованная ссылка повреждена. Данные не изменены.") }
    }
}
