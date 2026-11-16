package org.mcrf.client

import org.json.JSONArray
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
    internal const val FRESH_VERSION = "v0.17.1"
    internal const val FRESH_COMMIT = "f492a1a33b7581ad8f88118f97e0b866adda3cdc"
    private val GROUP_SEPARATOR = Regex("[,| \\n\\t\\r]+")

    /** No capability inferred from APK versionCode or an old diagnostic overlay. */
    internal fun supportsFreshProfile(version: String?, commit: String?): Boolean =
        version == FRESH_VERSION && commit == FRESH_COMMIT

    internal fun poolStartupMillis(data:JSONObject)=60_000L

    internal fun supportsScriptSource(version: String?, commit: String?): Boolean =
        version == FRESH_VERSION && commit == FRESH_COMMIT

    internal fun supportsVerification(version: String?, commit: String?, contract: Int,
                                      background: Boolean, diagnostic: Boolean): Boolean =
        !background && !diagnostic && contract == 1 && version == "v0.13.0" && commit == "4f9c786cdd1d9fcd0c133e2e0fec0c5a12522610"

    internal fun requiresFreshCore(data: JSONObject): Boolean = data.optString("type")=="openflux"

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
            require(OpenFluxJsonNesting.bounded(text)) { "Слишком глубокий или некорректный JSON профиля OpenFlux." }
            JSONObject(text).put("type", "openflux")
        } finally { inflater.end(); bytes.fill(0); packed.fill(0) }
        if (source.contains('#')) data.put("name", java.net.URLDecoder.decode(source.substringAfter('#').replace("+", "%2B"), "UTF-8"))
        return fromJson(source, data)
    }
    @JvmStatic fun fromJson(source: String, input: JSONObject): ServerImport.Server {
        val data = JSONObject(input.toString()); require(data.getString("type") == "openflux")
        listOf("mcrf_verification_ui","mcrf_own_inline_ui","mcrf_own_http_ui").forEach(data::remove)
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
            val value = transports.getJSONObject(i); val type = normalizeLegacyTransport(value)
            require(type in TRANSPORT_TYPES) { if(type=="fluxfast") "FluxFast отсутствует в официальном OpenFlux v0.17.1. Выберите транспорт сервера." else "OpenFlux v0.17.1 не поддерживает транспорт «${safeToken(type)}»." }
            if(type=="script")normalizeScript(value)
            if(value.has("priority")){
                val priority=value.get("priority")
                require(priority is Int || priority is Long && priority in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {"Приоритет транспорта должен быть целым 32-битным числом."}
            }
            require(!value.has("params") || value.optJSONObject("params") != null) { "Параметры транспорта должны быть JSON-объектом." }
            value.optJSONObject("params")?.let { params ->
                require(params.keys().asSequence().none { it in setOf("path", "pubkey", "exit", "is_exit", "listen") }) { "Путь, ключ и роль транспорта задаёт модуль OpenFlux." }
                require(!params.has("settings")||params.optJSONObject("settings")!=null) {"Настройки скрипта должны быть JSON-объектом."}
                params.optJSONObject("settings")?.let { settings ->
                    require(settings.keys().asSequence().all { settings.opt(it) is String }) { "Настройки скрипта должны быть строками." }
                }
            }
            value.optString("name").takeIf(String::isNotEmpty)?.let { require(it.length <= 128 && it.none(Char::isISOControl) && names.add(it)) }
            if (mode == "stream") require(type in setOf("cupsonline", "mailru"))
            if (type == "direct") {
                require(negotiate)
                val raw = value.getString("dial"); val address = URI("tcp://$raw")
                require(address.host != null && address.port in 1..65535 && address.userInfo == null && address.path.isEmpty() && address.query == null && address.fragment == null)
                if (host == "OpenFlux") { host = address.host.removeSurrounding("[", "]"); port = address.port }
            } else if(type == "script") {
                require(negotiate) { "Script-транспорт требует согласования и секрета от 16 символов." }
                val params=value.optJSONObject("params") ?: JSONObject().also {value.put("params",it)}
                val script=params.optString("name").removeSuffix(".js").removeSuffix(".flux")
                require(script in OFFICIAL_SCRIPTS) { "Нет закреплённого подписанного пакета для этого JS-протокола." }
                params.put("name",script)
                require(value.optString("url").length<=8192 && value.optString("url").none {it.isISOControl()})
                if (script == "cupsonline") {
                    if (value.optString("url").isNotBlank() || params.has("urls")) roomLists(value, "cupsonline")
                    if (host == "OpenFlux") host = "interview.cups.online"
                } else if (host == "OpenFlux") {
                    // An opaque script URL is not a DNS name or a destination bypass.
                    val address = runCatching { URI(value.optString("url")) }.getOrNull()
                    if (address?.scheme in setOf("http", "https") && address?.host != null && address.userInfo == null) {
                        host = address.host; port = if (address.port > 0) address.port else if (address.scheme == "http") 80 else 443
                    }
                }
            } else {
                val raw = value.getString("url")
                require(raw.isNotBlank() && raw.length <= 8192 && raw.none { it.isISOControl() && it !in "\n\t\r" })
                // Provider endpoints are metadata only. Never turn the destination
                // yandex.ru into a carrier URL, or rewrite a packed room list.
                if (type in setOf("cupsonline", "avito")) {
                    roomLists(value, type)
                    if (host == "OpenFlux") host = if (type == "avito") "code.avito.ru" else "interview.cups.online"
                } else {
                    val urls = carrierUrls(value); require(urls.size in 1..64)
                    urls.forEach { url ->
                        val address = URI(url)
                        require(address.scheme in setOf("http", "https") && address.host != null && address.userInfo == null && address.port != 0 && address.port <= 65535)
                        if (host == "OpenFlux") {
                            host = address.host
                            port = if (address.port > 0) address.port else if (address.scheme == "http") 80 else 443
                        }
                    }
                }
            }
        }
        val name = data.optString("name", "OpenFlux").ifBlank { "OpenFlux" }
        require(name.length <= 256 && name.none(Char::isISOControl))
        require(data.optString("context").length <= 8192 && data.optString("context").none(Char::isISOControl))
        return ServerImport.Server(source, name, "openflux", host, port, null, null, null, data)
    }
    internal val TRANSPORT_TYPES = listOf("direct", "yandex", "vyandex", "boards", "mailru", "cupsonline", "avito", "telemost", "script")
    // Exactly scripts-lock.json.packages; upstream source files alone are not packages.
    internal val OFFICIAL_SCRIPTS = listOf("yandex", "vyandex", "boards", "mailru", "cupsonline", "oneme-iceinject", "oneme-webrtc", "mtslink", "bitrix", "pruffme")
    internal const val OFFICIAL_SCRIPT_KEY="d8bf9c958b994c2faab886cade5f28213f254911f87abe5e34756a289ae91354"
    private fun safeToken(value:String)=value.takeIf {it.matches(Regex("[a-zA-Z0-9._-]{1,48}"))}?:"неизвестный"
    private fun carrierUrls(value:JSONObject):List<String> {
        val params=value.optJSONObject("params")
        if (params?.has("urls") == true) {
            val urls=requireNotNull(params.optJSONArray("urls")) { "urls: нужен список ссылок или списков комнат." }
            require(urls.length() in 1..64)
            return (0 until urls.length()).map { i ->
                require(urls.get(i) is String)
                urls.getString(i).also { require(it.isNotBlank() && it.length <= 8192) }
            }
        }
        return groupedUrls(value.getString("url"))
    }
    /** Match upstream PackRooms/parseRooms; validate without repacking the wire. */
    private fun roomLists(value:JSONObject,type:String) {
        val lists=carrierUrls(value); require(lists.size in 1..64)
        lists.forEach { raw ->
            val address=runCatching { URI(raw) }.getOrNull()
            val query=address?.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map { part ->
                java.net.URLDecoder.decode(part.substringBefore('='),"UTF-8") to
                    java.net.URLDecoder.decode(part.substringAfter('=',""),"UTF-8")
            }
            require(query.count {it.first=="rooms"}<=1 && query.count {it.first=="room"}<=1)
            val packed=query.firstOrNull {it.first=="rooms"}?.second?.takeIf(String::isNotEmpty)
            val single=query.firstOrNull {it.first=="room"}?.second?.takeIf(String::isNotEmpty)
            val rooms = if(packed==null && single!=null && type=="cupsonline") JSONArray().put(single) else {
                val encoded=packed?:raw
                require(encoded.matches(Regex("[A-Za-z0-9_-]+"))) { "Нужен исходный список комнат $type от сервера." }
                val decoded=Base64.getUrlDecoder().decode(encoded)
                val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString()
                require(OpenFluxJsonNesting.bounded(text)) { "Слишком глубокий или некорректный список комнат." }
                if(text.startsWith('{')) JSONObject(text).let { node ->
                    require(node.opt("v") == 2) { "Неизвестная версия списка комнат." };node.getJSONArray("rooms")
                } else JSONArray(text)
            }
            require(rooms.length() > 0 && (type!="avito" || rooms.length()>=2 && rooms.length()%2==0)) { "Нужен полный список комнат $type от сервера." }
            repeat(rooms.length()) { i -> require(rooms.get(i) is String && rooms.getString(i).isNotBlank() && rooms.getString(i).none(Char::isISOControl)) }
        }
    }
    /** Shapes emitted by official pre-0.17 clients: iOS called vyandex "volga" and mobile specs nested direct.dial. */
    private fun normalizeLegacyTransport(value:JSONObject):String {
        val raw=value.optString("type")
        val type=when(raw){"", "yandex-docs"->"yandex";"volga"->"vyandex";else->raw}
        if(type!=raw)value.put("type",type)
        if(type=="direct"&&!value.has("dial"))value.optJSONObject("params")?.optString("dial")?.takeIf(String::isNotBlank)?.let {value.put("dial",it)}
        return type
    }
    private fun normalizeScript(value:JSONObject){
        val params=value.optJSONObject("params")?:return
        if(params.has("pubkey"))require(params.getString("pubkey").equals(OFFICIAL_SCRIPT_KEY,true)){"Неизвестный ключ автора script-транспорта."}
        val path=params.optString("path")
        if(path.isNotEmpty()){
            // A share may name an official .js/.flux path from another device.
            // Resolve only its known package identity; never open imported paths.
            require(path.endsWith(".js")||path.endsWith(".flux")){"Нужен подписанный .js или .flux."}
            val id=path.replace('\\','/').substringAfterLast('/').removeSuffix(".js").removeSuffix(".flux").replace(Regex("-[0-9]+\\.[0-9]+\\.[0-9]+$"),"")
            require(id in OFFICIAL_SCRIPTS){"Неизвестный официальный script-пакет."}
            params.put("name",id)
        }
        params.remove("path");params.remove("pubkey")
    }
    @JvmStatic fun share(input: JSONObject): String {
        val data = requireNotNull(fromJson("openflux-json",JSONObject(input.toString()).put("type","openflux")).carrier)
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
