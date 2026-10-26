package org.mcrf.client

import org.json.JSONObject
import org.json.JSONArray
import java.net.URI
import java.net.URLDecoder

/** New-profile local policy only. Coordinator supplies VkRelayRuntime.defaultsForNewQwdtt callback. */
object CompatibilityImportQwdtt {
    fun hasCalls(raw: JSONObject): Boolean {
        for (field in listOf("hashes", "vkHashes")) {
            val values = raw.opt(field)
            if (values is JSONArray && (0 until values.length()).any { values.optString(it).isNotBlank() }) return true
            if (values is String && values.split(Regex("[,;+\\s]+")).any(String::isNotBlank)) return true
        }
        return false
    }
    fun applyNew(source: String, defaults: (JSONObject) -> JSONObject): String = try { transform(source, defaults, 0) }
        catch (_: Exception) { throw IllegalArgumentException("Не удалось подготовить новый профиль QWDTT. Проверь формат и источник звонков; данные не добавлены.") }
    private fun policy(raw: JSONObject, defaults: (JSONObject) -> JSONObject): JSONObject? {
        if (raw.optString("type") != "qwdtt") return null
        val output = JSONObject(raw.toString())
        // Only new imports pass through this policy. Never migrate saved profiles.
        if (!output.has("mode")) output.put("mode", "raw")
        if (raw.has("hash_source") || hasCalls(raw)) return output.takeIf { it.toString() != raw.toString() }
        val result = defaults(JSONObject(output.toString()))
        // Defaults cannot add account tokens or replace provider credentials/endpoint.
        for (key in listOf("hash_source", "hashes", "vkHashes")) if (result.has(key)) output.put(key, result.get(key))
        require(output.optString("hash_source") in setOf("manual", "auto")) { "Выбери источник звонков QWDTT." }
        if (hasCalls(output)) output.put("hash_source", "manual")
        return output
    }
    private fun transform(source: String, defaults: (JSONObject) -> JSONObject, depth: Int): String {
        require(depth <= 8 && source.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES)
        val text = CompatibilityImportLinks.unwrapDocument(source.trim())
        if (text.isNotEmpty() && !text.contains("://") && text.all { it.isWhitespace() || it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=_-" }) {
            val decoded = try { CompatibilityImportFormats.decodeBase64(text) } catch (_: Exception) { null }
            if (decoded != null && decoded != text) { val next = transform(decoded, defaults, depth + 1); return if (next != decoded) next else source }
        }
        if (text.startsWith('{') || (text.startsWith('[') && !text.startsWith("[Interface]", true))) {
            CompatibilityImportFormats.validateJsonDepth(text)
            var changed = false
            fun values(array: JSONArray): JSONArray = JSONArray().apply { repeat(array.length()) { i ->
                val item = array.get(i); val next = transform(item.toString(), defaults, depth + 1)
                if (next != item.toString()) changed = true
                put(if (item is JSONObject || item is JSONArray) org.json.JSONTokener(next).nextValue() else next)
            } }
            if (text.startsWith('[')) { val original = JSONArray(text); val out = values(original); return if (changed) out.toString() else source }
            val raw = JSONObject(text)
            policy(raw, defaults)?.let { return it.toString() }
            for (field in listOf("profiles", "servers")) raw.optJSONArray(field)?.let { raw.put(field, values(it)) }
            return if (changed) raw.toString() else source
        }
        if (text.contains('\n') && !text.startsWith("[Interface]", true)) {
            val lines = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith('#') }
            val transformed = lines.map { transform(it, defaults, depth + 1) }
            if (transformed != lines) return JSONArray(transformed).toString()
            return source
        }
        if (text.startsWith("qwdtt://", true) || text.startsWith("qwdtt:config?", true)) {
            val uri = URI(if (text.startsWith("qwdtt:config?", true)) "qwdtt://config?" + text.substringAfter('?') else text)
            val raw = JSONObject().put("type", "qwdtt")
            uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).forEach { field ->
                val parts = field.split('=', limit = 2); require(parts.size == 2)
                val key = URLDecoder.decode(parts[0], "UTF-8"); require(!raw.has(key))
                raw.put(key, URLDecoder.decode(if (uri.host == "config") parts[1] else parts[1].replace("+", "%2B"), "UTF-8"))
            }
            if (uri.host == "connect") {
                require(raw.optString("v") == "1"); raw.put("server", raw.getString("host")).put("server_port", raw.getInt("dtls"))
            } else require(uri.host == "config")
            if (raw.has("dtls_port")) raw.put("server_port", raw.getInt("dtls_port"))
            raw.put("password", raw.optString("password", raw.optString("pass")))
            if (!raw.has("name")) raw.put("name", raw.optString("remark", uri.rawFragment?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } ?: "QWDTT"))
            return policy(raw, defaults)?.toString() ?: source
        }
        return source
    }
}
