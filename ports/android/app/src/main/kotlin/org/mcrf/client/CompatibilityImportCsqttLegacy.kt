package org.mcrf.client

import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject
import org.json.JSONArray

/** Original public userinfo form; it carries no VK calls, which must be supplied separately. */
object CompatibilityImportCsqttLegacy {
    fun isLegacy(source: String): Boolean = source.startsWith("csqtt://", true) &&
        try { URI(source).rawUserInfo != null } catch (_: Exception) { false }
    fun raw(source: String): JSONObject {
        try {
            require(source.toByteArray(Charsets.UTF_8).size <= 65536)
            val uri = URI(source)
            require(uri.scheme.equals("csqtt", true) && uri.host != null && uri.port in 1..65535 && uri.rawUserInfo != null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty())
            val password = URLDecoder.decode(uri.rawUserInfo.replace("+", "%2B"), "UTF-8")
            require(password.isNotBlank() && password.none(Char::isISOControl))
            val node = JSONObject().put("type", "csqtt").put("server", uri.host.removeSurrounding("[", "]")).put("server_port", uri.port).put("password", password).put("hash_source", "manual")
            val supplied = hashSetOf<String>()
            uri.rawQuery?.split('&')?.forEach { field ->
                val parts = field.split('=', limit = 2); require(parts.size == 2)
                val key = URLDecoder.decode(parts[0].replace("+", "%2B"), "UTF-8")
                require(key in setOf("hashes", "vkHashes", "name") && supplied.add(key))
                node.put(key, URLDecoder.decode(parts[1].replace("+", "%2B"), "UTF-8"))
            }
            require(!(supplied.contains("hashes") && supplied.contains("vkHashes")))
            return node
        } catch (_: Exception) { throw IllegalArgumentException("Некорректная устаревшая ссылка CSQTT. Данные не добавлены.") }
    }
    fun materialize(source: String, manualCalls: List<String>): String {
        val node = raw(source)
        if (!CompatibilityImportQwdtt.hasCalls(node)) {
            require(manualCalls.isNotEmpty()) { "Ссылка CSQTT распознана, но не содержит звонков. Добавь ручные ссылки или хеши VK-звонков; вход в VK не требуется." }
            node.put("hashes", JSONArray(manualCalls))
        }
        return SwgProfile.fromJson("legacy-csqtt", node).carrier!!.toString()
    }
}
