package org.mcrf.client

import org.json.JSONObject

/** Decodes public routing data; DNS owner validates/applies order and datasets, never remote global policy. */
object HappRoutingLinks {
    data class Request(val action: String, val document: JSONObject?)
    fun isRouting(value: String) = value.startsWith("happ://routing/", true)||value.startsWith("incy://routing/", true)
    fun resource(value:String):String? {
        if(value.startsWith("https://",true))return RemoteRoutingConfig.https(value).toString()
        if(!isRouting(value))return null
        val content=value.substring("happ://routing/".length)
        if(content=="off")return null
        require(content.substringBefore('/') in setOf("add","onadd")&&content.contains('/'))
        var payload=content.substringAfter('/')
        if(payload.startsWith("https%3a%2f%2f",true))payload=java.net.URLDecoder.decode(payload,"UTF-8")
        return if(payload.startsWith("https://",true))RemoteRoutingConfig.https(payload).toString()else null
    }
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
        } catch (_: Exception) { throw IllegalArgumentException("Некорректная открытая ссылка маршрутов Happ / Incy. Действующие маршруты не изменены.") }
    }
}
