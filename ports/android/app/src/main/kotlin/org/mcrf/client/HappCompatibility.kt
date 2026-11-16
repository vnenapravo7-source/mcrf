package org.mcrf.client

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Public Happ interoperability only. No private keys, opaque decoder or remote policy execution. */
object HappCompatibility {
    const val CRYPTO_API = "https://crypto.happ.su/api-v2.php"
    const val ENCRYPTED_MESSAGE = "Это закрытая шифрованная ссылка Happ. Официальный декодер для MCRF недоступен. Запроси у провайдера обычную HTTPS-подписку или открытый профиль; данные не добавлены."
    fun isLink(value: String) = value.startsWith("happ://", true)
    fun isEncrypted(value: String) = isLink(value) && value.substring(7).substringBefore('/').lowercase(Locale.ROOT).matches(Regex("crypt(?:o)?[0-9]*"))
    fun rejectOpaque(value: String) {
        if (HappRoutingLinks.isRouting(value)) throw IllegalArgumentException("Это профиль маршрутов Happ, а не подключение. Импортируй его в настройках маршрутизации.")
        if (isEncrypted(value)) throw IllegalArgumentException(ENCRYPTED_MESSAGE)
        if (isLink(value)) throw IllegalArgumentException("Этот тип ссылки Happ не поддерживается. Используй открытый профиль или HTTPS-подписку.")
    }
    /** Produces the documented request, never sends it. UI must explain that Happ receives the private URL. */
    fun encryptionRequest(subscription: String, consentToSendUrl: Boolean): JSONObject {
        require(consentToSendUrl) { "Нужно отдельное согласие передать адрес подписки сервису Happ." }
        try {
            val uri = URI(subscription)
            require(subscription.length <= 8192 && uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null && uri.port != 0 && uri.port <= 65535)
        } catch (_: Exception) { throw IllegalArgumentException("Для шифрования Happ нужна корректная HTTPS-подписка без логина и фрагмента.") }
        return JSONObject().put("url", subscription)
    }
    /** Header values override body metadata. Provider instructions remain data and cannot override user consent. */
    @JvmOverloads fun metadata(body: String, headers: Map<String, String> = emptyMap()): JSONObject {
        require(body.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES)
        val fields = linkedMapOf<String, String>()
        val document = body.trim().removePrefix("\uFEFF").let { text ->
            if (text.isNotEmpty() && !text.contains("://") && !text.startsWith('{') && !text.startsWith('[') && !text.startsWith('#'))
                try { CompatibilityImportFormats.decodeBase64(text) } catch (_: Exception) { text }
            else text
        }
        document.lineSequence().filter { it.trimStart().startsWith('#') }.forEach { line ->
            val parts = line.trim().removePrefix("#").split(':', limit = 2)
            if (parts.size == 2 && parts[1].length <= 2048) fields[parts[0].trim().lowercase(Locale.ROOT)] = parts[1].trim()
        }
        headers.forEach { (key, value) -> if (value.length <= 2048) fields[key.lowercase(Locale.ROOT)] = value.trim() }
        val result = JSONObject()
        fields["subscription-userinfo"]?.split(';')?.forEach { item ->
            val parts = item.trim().split('=', limit = 2)
            if (parts.size == 2 && parts[0] in setOf("upload", "download", "total", "expire")) parts[1].trim().toLongOrNull()?.takeIf { it >= 0 }?.let { result.put(parts[0], it) }
        }
        fields["profile-device-limit"]?.toIntOrNull()?.takeIf { it > 0 }?.let { result.put("deviceLimit", it) }
        fields["profile-title"]?.let { raw ->
            val title = if (raw.startsWith("base64:")) try { CompatibilityImportFormats.decodeBase64(raw.substring(7)) } catch (_: Exception) { "" } else raw
            if (title.isNotBlank() && title.length <= 256 && title.none(Char::isISOControl)) result.put("title", title)
        }
        result.put("providerRequestsHwid", fields["subscription-always-hwid-enable"] in setOf("true", "1"))
        result.put("providerHidesSettings", fields["hide-settings"] in setOf("true", "1"))
        return result
    }
    /** DNS owner compiles these Xray data references; this class never downloads or renames datasets. */
    fun geoReferences(routing: JSONObject): Set<String> {
        val found = linkedSetOf<String>()
        fun visit(value: Any?) { when (value) {
            is JSONObject -> value.keys().forEach { visit(value.opt(it)) }
            is org.json.JSONArray -> repeat(value.length()) { visit(value.opt(it)) }
            is String -> if (value.matches(Regex("(?:ext:[A-Za-z0-9._-]+\\.dat:|geoip:|geosite:)[A-Za-z0-9_!@.-]+"))) found.add(value)
        } }
        visit(routing); return found
    }
}
