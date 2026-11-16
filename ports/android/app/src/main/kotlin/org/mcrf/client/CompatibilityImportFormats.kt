package org.mcrf.client

import org.json.JSONObject
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Public sing-box outbound and SIP008 node adapters. Unknown core formats fail without losing options. */
object CompatibilityImportFormats {
    class UnsupportedFormat(message: String) : IllegalArgumentException(message)
    private val ordinary = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "hysteria2", "tuic", "anytls", "ssh", "naive")
    fun decodeBase64(value: String): String {
        require(value.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES)
        val bytes = Base64.getDecoder().decode(value.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/'))
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    fun validateJsonDepth(source: String) {
        // JSONTokener uses NUL as end-of-input. An embedded raw NUL must not hide
        // trailing data; escaped "\\u0000" inside a JSON string is not rejected here.
        require('\u0000' !in source) {"Недопустимый нулевой символ в JSON."}
        var depth=0;var quote:Char?=null;var escaped=false;var index=0
        while(index<source.length){
            val char=source[index++]
            if(quote!=null){
                if(escaped)escaped=false else if(char=='\\')escaped=true else if(char==quote)quote=null
            } else when (char) {
                '"','\'' -> quote=char
                '/' -> when(source.getOrNull(index)){
                    '/' -> {index++;while(index<source.length&&source[index] !in "\r\n")index++}
                    '*' -> {
                        val end=source.indexOf("*/",index+1)
                        require(end>=0) {"Незавершённый комментарий JSON."};index=end+2
                    }
                }
                '#' -> while(index<source.length&&source[index] !in "\r\n")index++
                '{', '[' -> { depth++; require(depth <= 64) { "Слишком глубокая вложенность JSON." } }
                '}', ']' -> {depth--;require(depth>=0) {"Неверные скобки JSON."}}
            }
        }
        require(depth==0&&quote==null) {"Незавершённый JSON."}
    }
    fun node(source: String, raw: JSONObject): ServerImport.Server? {
        if (!raw.has("type") && raw.has("server") && raw.has("server_port") && raw.has("method") && raw.has("password")) {
            val outbound = JSONObject().put("type", "shadowsocks").put("server", raw.getString("server")).put("server_port", raw.getInt("server_port"))
                .put("method", raw.getString("method")).put("password", raw.getString("password"))
            listOf("plugin", "plugin_opts").forEach { if (raw.has(it) && raw.optString(it).isNotEmpty()) outbound.put(it, raw.getString(it)) }
            outbound.put("tag", raw.optString("remarks", "Shadowsocks"))
            return node(source, outbound)
        }
        if (raw.optString("type") !in ordinary) return null
        val host = raw.getString("server"); val port = raw.getInt("server_port")
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/@\\" } && port in 1..65535)
        val name = raw.optString("name", raw.optString("tag", host)).ifBlank { host }
        require(name.length <= 256 && name.none(Char::isISOControl))
        val outbound = JSONObject(raw.toString()).apply { remove("name"); put("tag", "imported") }
        return ServerImport.Server(source, name, raw.getString("type"), host, port, outbound, null, null)
    }
    fun rejectForeign(config: JSONObject) {
        val outbounds = config.optJSONArray("outbounds") ?: return
        if ((0 until outbounds.length()).any { outbounds.optJSONObject(it)?.has("protocol") == true })
            throw UnsupportedFormat("Это JSON Xray. Импорт полного профиля Xray требует отдельного совместимого ядра; используй стандартные ссылки или профиль sing-box.")
    }
}
