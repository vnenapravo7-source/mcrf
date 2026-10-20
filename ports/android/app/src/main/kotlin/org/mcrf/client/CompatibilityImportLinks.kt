package org.mcrf.client

import java.net.URI
import java.net.URLDecoder

/** Documented v2RayTun import wrapper; retains inner URI escapes and literal plus signs. */
object CompatibilityImportLinks {
    private const val PREFIX = "v2raytun://import/"
    fun unwrapDocument(value: String) = if (value.contains('\n') || value.contains('\r')) value else unwrap(value)
    fun unwrap(value: String): String {
        if (!value.startsWith("v2raytun://", true)) return value
        require(value.startsWith(PREFIX, true) && value.toByteArray(Charsets.UTF_8).size <= 65536 && value.none(Char::isISOControl)) { "Поддерживается только открытая ссылка v2RayTun import. Закрытая crypt-ссылка не декодируется." }
        var payload = value.substring(PREFIX.length)
        if (!payload.contains("://")) payload = try { URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { throw IllegalArgumentException("Повреждена ссылка импорта v2RayTun.") }
        require(!payload.startsWith("v2raytun://", true) && payload.none(Char::isISOControl) && payload.isNotBlank()) { "Вложенная или пустая ссылка v2RayTun не поддерживается." }
        val scheme = payload.substringBefore("://").lowercase(java.util.Locale.ROOT)
        require(scheme in ImportLink.schemes - "v2raytun" || scheme == "https") { "Неподдерживаемое содержимое ссылки v2RayTun." }
        if (scheme == "https") {
            try { val uri = URI(payload); require(uri.host != null && uri.userInfo == null && uri.fragment == null && uri.port != 0 && uri.port <= 65535) }
            catch (_: Exception) { throw IllegalArgumentException("v2RayTun: подписка должна быть корректной HTTPS-ссылкой.") }
        }
        return payload
    }
}
