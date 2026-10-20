package org.mcrf.client

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Official v1: raw DEFLATE + base64url, bounded before native parsing. */
object OpenFluxProfile {
    private const val PREFIX = "openflux://v1/"
    private const val MAX_PAYLOAD = 16384
    @JvmStatic fun link(source: String): ServerImport.Server {
        require(source.startsWith(PREFIX) && source.length <= 32768)
        val body = source.substring(PREFIX.length).substringBefore('#').filterNot { it.isWhitespace() || it == '\u00a0' || it == '\u200b' }
            .replace('+', '-').replace('/', '_').trimEnd('=')
        val packed = Base64.getUrlDecoder().decode(body)
        val inflater = Inflater(true)
        val bytes = ByteArray(MAX_PAYLOAD+1)
        val count = try {
            inflater.setInput(packed); var total = 0
            while (!inflater.finished() && total < bytes.size) {
                val read = inflater.inflate(bytes, total, bytes.size-total)
                require(read > 0) { "Повреждён профиль OpenFlux." }; total += read
            }
            require(inflater.finished() && inflater.remaining == 0 && total <= MAX_PAYLOAD); total
        } finally { inflater.end() }
        val data = JSONObject(String(bytes, 0, count, Charsets.UTF_8)).put("type", "openflux")
        if (source.contains('#')) data.put("name", java.net.URLDecoder.decode(source.substringAfter('#').replace("+", "%2B"), "UTF-8"))
        return fromJson(source, data)
    }
    @JvmStatic fun fromJson(source: String, input: JSONObject): ServerImport.Server {
        val data = JSONObject(input.toString()); require(data.getString("type") == "openflux")
        require(data.toString().toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD)
        val mode = data.optString("mode"); require(mode in setOf("", "stream"))
        val negotiate = data.optBoolean("negotiate", false); val secret = data.optString("secret")
        require(secret.isEmpty() || secret.length >= 16)
        require(!negotiate || secret.length >= 16)
        val codec = data.optString("codec", "batched"); require(codec in setOf("", "batched", "legacy"))
        val transports = data.getJSONArray("transports"); require(transports.length() in 1..64)
        require(transports.length() == 1 || negotiate)
        if (mode == "stream") require(transports.length() == 1 && !negotiate && secret.isEmpty())
        val names = hashSetOf<String>(); var host = "OpenFlux"; var port = 443
        repeat(transports.length()) { i ->
            val value = transports.getJSONObject(i); val type = value.getString("type")
            require(type in setOf("direct", "yandex", "vyandex", "boards", "mailru", "cupsonline"))
            value.optString("name").takeIf(String::isNotEmpty)?.let { require(it.length <= 128 && it.none(Char::isISOControl) && names.add(it)) }
            if (mode == "stream") require(type in setOf("cupsonline", "mailru"))
            if (type == "direct") {
                require(negotiate)
                val raw = value.getString("dial"); val address = URI("tcp://$raw")
                require(address.host != null && address.port in 1..65535 && address.userInfo == null && address.path.isEmpty() && address.query == null && address.fragment == null)
                if (host == "OpenFlux") { host = address.host.removeSurrounding("[", "]"); port = address.port }
            } else {
                val raw = value.getString("url")
                require(raw.isNotBlank() && raw.length <= 8192 && raw.none(Char::isISOControl))
                // Cups can carry a list of rooms, not just one document URL.
                if (type != "cupsonline") {
                    val address = URI(raw)
                    require(address.scheme in setOf("http", "https") && address.host != null && address.userInfo == null)
                    if (host == "OpenFlux") host = address.host
                }
            }
        }
        val name = data.optString("name", "OpenFlux").ifBlank { "OpenFlux" }
        require(name.length <= 256 && name.none(Char::isISOControl))
        require(data.optString("context").length <= 8192 && data.optString("context").none(Char::isISOControl))
        return ServerImport.Server(source, name, "openflux", host, port, null, null, null, data)
    }
    @JvmStatic fun share(input: JSONObject): String {
        val data = JSONObject(input.toString()); data.put("type", "openflux"); fromJson("openflux-json", data)
        data.remove("type"); data.remove("outbound_tag")
        val bytes = data.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= MAX_PAYLOAD)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(bytes); deflater.finish()
            val result = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (!deflater.finished()) { val count = deflater.deflate(buffer); require(count > 0); result.write(buffer, 0, count) }
            return PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(result.toByteArray())
        } finally { deflater.end() }
    }
}
