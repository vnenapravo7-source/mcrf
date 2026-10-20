package org.mcrf.client

import java.util.Base64
import org.json.JSONObject

/** MCRF-only password share. Same audited AES-GCM envelope as backups, not a Happ crypt decoder. */
object CompatibilityImportEncrypted {
    const val PREFIX = "mcrf://encrypted/v1/"
    const val MAX_SOURCE = ServerImport.MAX_BYTES / 2
    fun isEncrypted(value: String) = value.startsWith(PREFIX)
    fun encrypt(source: String, password: CharArray): String {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE) { "Шифрованный обмен ограничен профилем до 2 МБ." }
        require(!isEncrypted(source)) { "Нельзя повторно шифровать шифрованную ссылку." }
        val bytes = JSONObject().put("format", "mcrf-share").put("version", 1).put("source", source).toString().toByteArray(Charsets.UTF_8)
        return try { PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(BackupCodec.encrypt(bytes, password)) } finally { bytes.fill(0) }
    }
    fun decrypt(link: String, password: CharArray): String {
        require(isEncrypted(link) && link.length <= ServerImport.MAX_BYTES * 2) { "Некорректная шифрованная ссылка MCRF." }
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
