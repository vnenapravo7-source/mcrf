package org.mcrf.client

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Official v1: raw DEFLATE + base64url, bounded before native parsing. */
object OpenFluxProfile {
    private const val PREFIX = "openflux://v1/"
    private const val MAX_PAYLOAD = 16384
    internal const val FRESH_VERSION = "v0.13.0"
    internal const val FRESH_COMMIT = "4f9c786cdd1d9fcd0c133e2e0fec0c5a12522610"
    internal const val TEAM_VERSION = "v0.16.0"
    internal const val TEAM_COMMIT = "5a46f2f1f178637538542a36c7f086f26df86bcd"
    private val GROUP_SEPARATOR = Regex("[,| \\n\\t\\r]+")

    /** No capability inferred from APK versionCode or an old diagnostic overlay. */
    internal fun supportsFreshProfile(version: String?, commit: String?): Boolean =
        version == FRESH_VERSION && commit == FRESH_COMMIT || supportsTeamProfile(version,commit)

    internal fun supportsTeamProfile(version:String?,commit:String?) = version==TEAM_VERSION&&commit==TEAM_COMMIT
    internal fun poolStartupMillis(data:JSONObject)=if(requiresTeamCore(data))60_000L else 15_000L
    internal fun requiresTeamCore(data:JSONObject):Boolean = data.getJSONArray("transports").let {t->
        (0 until t.length()).any {t.getJSONObject(it).optString("type")=="fluxfast"}
    }

    internal fun supportsScriptSource(version: String?, commit: String?): Boolean =
        version == FRESH_VERSION && commit == FRESH_COMMIT ||
            (version == "v0.7.3" && commit == "f12e981629a18c8c01007d59ac002254c02972bb")

    internal fun supportsVerification(version: String?, commit: String?, contract: Int,
                                      background: Boolean, diagnostic: Boolean): Boolean =
        !background && !diagnostic && contract == 1 && version == FRESH_VERSION && commit == FRESH_COMMIT

    internal fun requiresFreshCore(data: JSONObject): Boolean {
        val transports = data.getJSONArray("transports")
        return (0 until transports.length()).any { i ->
            val value = transports.getJSONObject(i)
            val type = value.getString("type")
            type in setOf("avito", "telemost", "fluxfast") ||
                (type !in setOf("direct", "cupsonline") && groupedUrls(value.getString("url")).size > 1)
        }
    }

    private fun groupedUrls(raw: String): List<String> = raw.split(GROUP_SEPARATOR).filter(String::isNotBlank)
    @JvmStatic fun link(source: String): ServerImport.Server {
        require(source.startsWith(PREFIX) && source.length <= 32768)
        val body = source.substring(PREFIX.length).substringBefore('#').filterNot { it.isWhitespace() || it == '\u00a0' || it == '\u200b' }
            .replace('+', '-').replace('/', '_').trimEnd('=')
        val packed = Base64.getUrlDecoder().decode(body)
        val inflater = Inflater(true)
        val bytes = ByteArray(MAX_PAYLOAD+1)
        val data = try {
            inflater.setInput(packed); var total = 0
            while (!inflater.finished() && total < bytes.size) {
                val read = inflater.inflate(bytes, total, bytes.size-total)
                require(read > 0) { "Повреждён профиль OpenFlux." }; total += read
            }
            require(inflater.finished() && inflater.remaining == 0 && total <= MAX_PAYLOAD)
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, total)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Повреждена кодировка профиля OpenFlux.")
            }
            JSONObject(text).put("type", "openflux")
        } finally { inflater.end(); bytes.fill(0); packed.fill(0) }
        if (source.contains('#')) data.put("name", java.net.URLDecoder.decode(source.substringAfter('#').replace("+", "%2B"), "UTF-8"))
        return fromJson(source, data)
    }
    @JvmStatic fun fromJson(source: String, input: JSONObject): ServerImport.Server {
        val data = JSONObject(input.toString()); require(data.getString("type") == "openflux")
        data.remove("mcrf_verification_ui") // Host capability, never imported from a share link.
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
            require(type in setOf("direct", "fluxfast", "yandex", "vyandex", "boards", "mailru", "cupsonline", "avito", "telemost"))
            value.optString("name").takeIf(String::isNotEmpty)?.let { require(it.length <= 128 && it.none(Char::isISOControl) && names.add(it)) }
            if (mode == "stream") require(type in setOf("cupsonline", "mailru"))
            if (type == "direct" || type == "fluxfast") {
                require(negotiate)
                val raw = value.getString("dial"); val address = URI("${if(type=="fluxfast")"udp"else"tcp"}://$raw")
                require(address.host != null && address.port in 1..65535 && address.userInfo == null && address.path.isEmpty() && address.query == null && address.fragment == null)
                if (host == "OpenFlux") { host = address.host.removeSurrounding("[", "]"); port = address.port }
            } else {
                val raw = value.getString("url")
                require(raw.isNotBlank() && raw.length <= 8192 && raw.none { it.isISOControl() && it !in "\n\t\r" })
                // Cups/Avito carry opaque room lists; other carriers can stripe URLs.
                if (type !in setOf("cupsonline", "avito")) {
                    val urls = groupedUrls(raw); require(urls.size in 1..64)
                    urls.forEach { url ->
                        val address = URI(url)
                        require(address.scheme in setOf("http", "https") && address.host != null && address.userInfo == null)
                        if (host == "OpenFlux") host = address.host
                    }
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
        data.remove("type"); data.remove("outbound_tag"); data.remove("mcrf_verification_ui")
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
