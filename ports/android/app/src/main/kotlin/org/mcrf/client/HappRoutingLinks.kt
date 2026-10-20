package org.mcrf.client

import org.json.JSONObject

/** Decodes public routing data; DNS owner validates/applies order and datasets, never remote global policy. */
object HappRoutingLinks {
    data class Request(val action: String, val document: JSONObject?)
    fun isRouting(value: String) = value.startsWith("happ://routing/", true)
    fun parse(value: String): Request {
        require(value.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES && isRouting(value))
        try {
            val content = value.substring("happ://routing/".length)
            if (content == "off") return Request("off", null)
            val action = content.substringBefore('/'); require(action in setOf("add", "onadd") && content.contains('/'))
            val source = CompatibilityImportFormats.decodeBase64(content.substringAfter('/'))
            CompatibilityImportFormats.validateJsonDepth(source)
            val token = org.json.JSONTokener(source)
            val document = token.nextValue() as JSONObject; require(token.nextClean() == '\u0000')
            return Request(action, document)
        } catch (_: Exception) { throw IllegalArgumentException("Некорректная открытая ссылка маршрутов Happ. Действующие маршруты не изменены.") }
    }
}
