package org.mcrf.client

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/** Protocol contract adapted from qThrone v1.4.2-beta.2 (GPL-3.0). */
object VkRelayProtocol {
    const val LOGIN_URL = "https://vk.ru/"
    enum class HashSource { MANUAL, AUTOMATIC }
    data class Authorization(val token: String, val user: String, val expiresIn: Long)
    fun trusted(url: String): Boolean = runCatching {
        val u = URI(url); val h = u.host.orEmpty().lowercase()
        u.scheme == "https" && u.userInfo == null && u.port in listOf(-1,443) &&
            (h == "vk.ru" || h.endsWith(".vk.ru") || h == "vk.com" || h.endsWith(".vk.com"))
    }.getOrDefault(false)
    fun newState() = UUID.randomUUID().toString()
    fun isCallback(url: String): Boolean = runCatching {
        val u=URI(url)
        trusted(url) && u.host in listOf("oauth.vk.com","oauth.vk.ru") && u.path=="/blank.html"
    }.getOrDefault(false)
    fun authorizeUrl(state: String): String {
        require(Regex("[a-zA-Z0-9-]{16,128}").matches(state))
        return "https://oauth.vk.com/authorize?client_id=6287487&display=mobile&redirect_uri=https%3A%2F%2Foauth.vk.com%2Fblank.html&response_type=token&scope=messages&v=5.199&state=$state"
    }
    fun authorization(url: String, state: String): Authorization? = runCatching {
        val u = URI(url)
        if(!isCallback(url)) return null
        val pairs = u.rawFragment.orEmpty().split('&').map { it.split('=',limit=2) }
        if(pairs.any { it.size != 2 }) return null
        val fields = pairs.map { URLDecoder.decode(it[0],"UTF-8") to URLDecoder.decode(it[1],"UTF-8") }
        if(fields.map { it.first }.distinct().size != fields.size) return null
        val m = fields.toMap(); val token = m["access_token"].orEmpty()
        if(m["state"] != state || token.isBlank() || token.length > 8192 || token.any { it.isISOControl() }) return null
        val user=m["user_id"].orEmpty()
        if(!Regex("[1-9][0-9]{0,31}").matches(user)) return null
        Authorization(token,user,(m["expires_in"]?.toLongOrNull() ?: 0).coerceAtLeast(0))
    }.getOrNull()
    fun hash(value: String, kind: String = "qwdtt"): String {
        val input = value.trim()
        val result = if(input.startsWith("https://")) {
            val u = URI(input)
            require(u.scheme == "https" && u.host in listOf("vk.ru","vk.com") && u.port in listOf(-1,443) && u.userInfo == null && u.query == null && u.fragment == null && u.path.startsWith("/call/join/")) { "Нужна ссылка https://vk.ru/call/join/…" }
            u.path.removePrefix("/call/join/")
        } else input
        require(Regex("[A-Za-z0-9_=-]{16,512}").matches(result)) { "Некорректный хеш звонка VK" }
        if(kind in listOf("wdtt","csqtt")) require(runCatching { Base64.getUrlDecoder().decode(result).size == 32 }.getOrDefault(false)) { "Для WDTT/CSQTT нужен 32-байтный хеш звонка" }
        return result
    }
    fun count(value: Int,kind: String): Int {
        require(kind in setOf("qwdtt","wdtt","csqtt"))
        require(value in 1..if(kind=="qwdtt") 32 else 6) { "Количество звонков вне диапазона протокола" };return value
    }
    fun calls(values: List<String>, kind: String): List<String> {
        require(kind in setOf("qwdtt","wdtt","csqtt")) { "Неизвестный протокол звонков" }
        val max=if(kind=="qwdtt") 32 else 6
        require(values.size in 1..max) { "Для ${kind.uppercase()} укажите от 1 до $max звонков" }
        val hashes = values.map { hash(it,kind) }
        require(hashes.distinct().size == hashes.size) { "Ссылки звонков повторяются" }
        return hashes.map { "https://vk.ru/call/join/$it" }
    }
}

