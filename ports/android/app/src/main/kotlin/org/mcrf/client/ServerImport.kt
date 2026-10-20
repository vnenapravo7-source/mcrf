package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.*

/** The same import contract and link corpus as the portable client. */
object ServerImport {
    const val MAX_BYTES = 4 * 1024 * 1024
    class Server @JvmOverloads constructor(
        @JvmField val original: String, @JvmField val name: String, @JvmField val protocol: String,
        @JvmField val host: String, @JvmField val port: Int, @JvmField val outbound: JSONObject?,
        @JvmField val endpoint: JSONObject?, @JvmField val configuration: JSONObject?, @JvmField val carrier: JSONObject? = null,
        @JvmField val profileDns: List<String> = emptyList()
    ) {
        @JvmField val id = MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8)).joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }
        fun needsEngine() = protocol in setOf("openflux", "csqtt", "wdtt", "qwdtt", "olcrtc", "dnstt",S3BypassProfile.KIND)
        fun label(): String { val transport = outbound?.optJSONObject("transport")?.optString("type", "").orEmpty()
            return (if(protocol==S3BypassProfile.KIND)"S3 Bypass"else protocol.uppercase(Locale.ROOT)) + if(carrier?.let(Csqtt25Profile::isProfile)==true) " · 2.5" else if (transport.isEmpty()) "" else " · ${transport.uppercase(Locale.ROOT)}" }
    }
    @JvmStatic fun read(source: String): List<Server> = readDocument(source, 0)
    @JvmStatic fun readNew(source: String, defaults: (JSONObject) -> JSONObject): List<Server> = read(CompatibilityImportQwdtt.applyNew(source, defaults))
    private fun readDocument(source: String, depth: Int): List<Server> {
        require(depth <= 8) { "Слишком глубокая вложенность импорта." }
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Импорт больше 4 МБ." }
        var text = CompatibilityImportLinks.unwrapDocument(source.trim()).replace(Regex("(?im)^qwdtt:config\\?"), "qwdtt://config?"); if (text.isEmpty()) return emptyList()
        if(text.startsWith("s3bypass://",true)){
            require(text.length<=90000)
            val decoded=CompatibilityImportFormats.decodeBase64(text.substringAfter("://"))
            require(decoded.toByteArray(Charsets.UTF_8).size<=65536)
            return readDocument(decoded,depth+1).also {require(it.size==1&&it.single().protocol==S3BypassProfile.KIND)}
        }
        HappCompatibility.rejectOpaque(text)
        require(!CompatibilityImportEncrypted.isEncrypted(text)) { "Для шифрованной ссылки MCRF нужен пароль." }
        if(text.startsWith("csqtt://",true)&&text.substring(8).matches(Regex("[A-Za-z0-9+/_=\\-\\r\\n ]+")))return listOf(Csqtt25Profile.encryptedLink(text))
        // Decode once, then classify again: encoded INI/JSON must not become a list of URI lines.
        if (!text.contains("://") && !text.startsWith('{') && !text.startsWith('[')) {
            text = try { decode64(text.filterNot(Char::isWhitespace)).trim() } catch (_: Exception) { throw IllegalArgumentException("Некорректный формат профиля или подписки.") }
            HappCompatibility.rejectOpaque(text)
        }
        if (text.startsWith('{') || (text.startsWith('[') && !text.startsWith("[Interface]", true))) CompatibilityImportFormats.validateJsonDepth(text)
        try {
            if (text.startsWith('[') && !text.startsWith("[Interface]", true)) {
                val list = org.json.JSONTokener(text).let { token -> (token.nextValue() as JSONArray).also { require(token.nextClean() == '\u0000') } }
                require(list.length() in 1..1000)
                return readCollection(list, depth)
            }
            if (text.startsWith('{')) {
                val config = org.json.JSONTokener(text).let { token -> (token.nextValue() as JSONObject).also { require(token.nextClean() == '\u0000') } }
                if(S3BypassProfile.isProfile(config))return listOf(S3BypassProfile.fromJson(text,config))
                val profiles = config.optJSONArray("profiles") ?: config.optJSONArray("servers")
                if (profiles != null && !config.has("outbounds")) {
                    require(profiles.length() in 1..1000)
                    return readCollection(profiles, depth)
                }
                if (!config.has("type") && config.has("peer") && (config.has("hashes") || config.has("vkHashes"))) config.put("type", "qwdtt")
                if (config.optString("type") in setOf("olcrtc", "dnstt", "openflux") + SwgProfile.TYPES) return listOf(CarrierProfile.fromJson(text, config))
                CompatibilityImportFormats.node(text, config)?.let { return listOf(it) }
                CompatibilityImportFormats.rejectForeign(config)
                ProfileConfig.build(text, true, 10808); val route = config.optJSONObject("route")
                return listOf(Server(text, config.optString("mcrf_name", route?.optString("final", "Профиль sing-box") ?: "Профиль sing-box"), "sing-box", "Конфигурация", 0, null, null, config.apply { remove("mcrf_name") }))
            }
            if (text.startsWith("[Interface]", true)) return listOf(wireGuard(text, null, null))
        } catch (error: Exception) { if (error is CompatibilityImportFormats.UnsupportedFormat) throw error; throw IllegalArgumentException("Некорректный формат профиля или подписки.") }
        val entries = mutableListOf<Server>(); val ids = hashSetOf<String>()
        text.lines().forEachIndexed { i, raw -> val line = raw.trim()
            if (line.isNotEmpty() && !line.startsWith('#')) {
                if (CompatibilityImportCsqttLegacy.isLegacy(line)) {
                    val supplied = CompatibilityImportCsqttLegacy.raw(line)
                    require(CompatibilityImportQwdtt.hasCalls(supplied)) { "Ссылка CSQTT распознана, но не содержит звонков. Добавь ручные ссылки или хеши VK-звонков; вход в VK не требуется." }
                }
                try { val entry = link(line); if (ids.add(entry.id)) entries.add(entry) }
                catch (_: Exception) { throw IllegalArgumentException("Некорректное подключение в строке ${i+1}. Проверь формат ссылки и обязательные поля.") }
            }
        }
        require(entries.isNotEmpty()) { "В импорте нет подключений." }; return entries
    }
    private fun readCollection(values: JSONArray, depth: Int): List<Server> {
        val entries = mutableListOf<Server>()
        repeat(values.length()) { i -> entries.addAll(readDocument(values.get(i).toString(), depth + 1)); require(entries.size <= 1000) { "В импорте больше 1000 подключений." } }
        return entries.distinctBy { it.id }
    }
    @JvmStatic fun build(entries: List<Server>, selectedId: String, automatic: Boolean, interval: Int, tolerance: Int, dns: String): JSONObject {
        require(interval in 5..86400 && tolerance in 0..10000) { "Некорректные параметры автовыбора." }
        val selected = entries.filter { if (automatic) !it.needsEngine() else it.id == selectedId }
        require(selected.isNotEmpty()) { "Выбери подключение в разделе «Серверы»." }
        if (!automatic && selected.size == 1 && selected[0].carrier != null) {
            val entry = selected[0]
            val socks = JSONObject().put("type", "socks").put("server", "127.0.0.1").put("server_port", 1).put("version", "5")
            val bridge = Server(entry.original, entry.name, "socks", entry.host, entry.port, socks, null, null)
            return build(listOf(bridge), bridge.id, false, interval, tolerance, dns)
                .put("mcrf_carrier", JSONObject(entry.carrier!!.toString()).put("outbound_tag", tag(entry)))
        }
        selected.forEach { entry ->
            require(!entry.needsEngine()) { "Для этого подключения нужен дополнительный движок." }
            entry.configuration?.let { require(selected.size == 1) { "Профиль sing-box со своими маршрутами выбирается отдельно от группы автовыбора." }; return JSONObject(it.toString()) }
        }
        val outputs = JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")); val endpoints = JSONArray(); val tags = JSONArray()
        selected.forEach { entry -> val tag = tag(entry); tags.put(tag)
            val node = JSONObject((entry.outbound ?: entry.endpoint!!).toString()).put("tag", tag)
                .put("domain_resolver", JSONObject().put("server", "bootstrap").put("strategy", "prefer_ipv4"))
            if (entry.endpoint == null) outputs.put(node) else endpoints.put(node)
        }
        val finalTag = if (selected.size > 1) "proxy" else tags.getString(0)
        if (selected.size > 1) outputs.put(JSONObject().put("type", "urltest").put("tag", finalTag).put("outbounds", tags)
            .put("url", "https://www.gstatic.com/generate_204").put("interval", "${interval}s").put("tolerance", tolerance))
        return JSONObject().put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject().put("servers", dnsServers(dns)).put("final", "chosen"))
            .put("outbounds", outputs).put("route", JSONObject().put("final", finalTag).put("default_domain_resolver", "chosen")
                .put("rules", JSONArray().put(JSONObject().put("action", "sniff")).put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))))
            .apply { if (endpoints.length() > 0) put("endpoints", endpoints); CompatibilityImportDns.install(this, CompatibilityImportDns.common(selected)) }
    }
    @JvmStatic fun tag(server: Server) = "node-${server.id.take(16)}"
    private fun dnsServers(provider: String): JSONArray {
        val chosen = JSONObject().put("tag", "chosen")
        if (provider == "Системный") chosen.put("type", "local") else {
            require(provider in setOf("Cloudflare", "Google")) { "Неизвестный DNS-провайдер." }
            chosen.put("type", "https").put("server", if (provider == "Google") "8.8.8.8" else "1.1.1.1").put("server_port", 443).put("path", "/dns-query")
                .put("tls", JSONObject().put("enabled", true).put("server_name", if (provider == "Google") "dns.google" else "cloudflare-dns.com"))
        }
        return JSONArray().put(chosen).put(JSONObject().put("type", "local").put("tag", "bootstrap"))
    }
    private fun link(original: String): Server {
        if (original.startsWith("v2raytun://", true)) return link(CompatibilityImportLinks.unwrap(original))
        require(original.toByteArray(Charsets.UTF_8).size <= 65536)
        var link = original; val scheme = link.substringBefore("://").lowercase(Locale.ROOT)
        when (scheme) { "olcrtc" -> return CarrierProfile.olc(link); "dnstt" -> return CarrierProfile.dnstt(link); "openflux" -> return OpenFluxProfile.link(link); "vmess" -> return vmess(link) }
        if (scheme in SwgProfile.TYPES) return SwgProfile.link(link)
        if (scheme == "ss" && !link.substringBefore('#').contains('@')) {
            val parts = link.substring(5).split('#', limit = 2); link = "ss://${decode64(parts[0])}" + if (parts.size == 2) "#${parts[1]}" else ""
        }
        val uri = URI(link); val host = required(uri.host).replace("[", "").replace("]", "")
        val port = if (uri.port < 1) when (scheme) { "ssh" -> 22; "http" -> 80; else -> 443 } else uri.port
        require(port <= 65535)
        val q = query(uri.rawQuery); val user = decode(uri.rawUserInfo); val name = decode(uri.rawFragment).ifEmpty { host }
        if (scheme in setOf("wg", "wireguard", "awg")) {
            return wireGuard(link, name, link)
        }
        val protocol = when (scheme) { "hy2" -> "hysteria2"; "naive+https" -> "naive"; else -> scheme }
        val output = JSONObject().put("type", protocol).put("server", host).put("server_port", port).put("tag", "imported"); val auth = user.split(':', limit = 2)
        when (protocol) {
            "vless" -> { UUID.fromString(user); output.put("uuid", user); add(output, "flow", q["flow"]); if (q["security"] in setOf("tls", "reality")) output.put("tls", tls(host, q, false)) }
            "trojan", "anytls" -> output.put("password", required(user)).put("tls", tls(host, q, false))
            "hysteria2" -> { output.put("password", required(user)).put("tls", tls(host, q, true)); q["obfs"]?.let { output.put("obfs", JSONObject().put("type", it).put("password", q["obfs-password"] ?: "")) }
                listOf("up", "down").forEach { speed -> q["${speed}mbps"]?.let { output.put("${speed}_mbps", it.toInt()) } } }
            "hysteria" -> { output.put("auth_str", q["auth"] ?: user).put("up", q["up"] ?: "${q["upmbps"] ?: "100"} Mbps").put("down", q["down"] ?: "${q["downmbps"] ?: "100"} Mbps").put("tls", tls(host, q, true)); add(output, "obfs", q["obfs"]) }
            "tuic" -> { UUID.fromString(auth[0]); require(auth.size == 2); output.put("uuid", auth[0]).put("password", auth[1]).put("tls", tls(host, q, true)); add(output, "congestion_control", q["congestion_control"]); add(output, "udp_relay_mode", q["udp_relay_mode"]) }
            "ss" -> { val ss = (if (user.contains(':')) user else decode64(user)).split(':', limit = 2); require(ss.size == 2)
                output.put("type", "shadowsocks").put("method", ss[0]).put("password", ss[1]); q["plugin"]?.let { val pieces = it.split(';', limit = 2); output.put("plugin", pieces[0]); if (pieces.size == 2) output.put("plugin_opts", pieces[1]) } }
            "socks", "socks5", "http", "https", "naive" -> {
                output.put("type", if (protocol.startsWith("socks")) "socks" else if (protocol == "https") "http" else protocol)
                if (protocol.startsWith("socks")) output.put("version", "5")
                if (user.isNotEmpty()) output.put("username", auth[0]); if (auth.size == 2) output.put("password", auth[1])
                if (protocol in setOf("https", "naive")) output.put("tls", tls(host, q, false))
            }
            "ssh" -> { output.put("user", required(auth[0])); if (auth.size == 2) output.put("password", auth[1]) }
            else -> throw IllegalArgumentException()
        }
        if (protocol in setOf("vless", "trojan")) transport(q)?.let { output.put("transport", it) }
        return Server(link, name, protocol, host, port, output, null, null)
    }
    private fun vmess(link: String): Server {
        val data = JSONObject(decode64(link.substring(8).substringBefore('#'))); val host = required(data.optString("add", "")); val uuid = data.getString("id"); UUID.fromString(uuid)
        val port = data.get("port").toString().toInt(); require(port in 1..65535)
        val output = JSONObject().put("type", "vmess").put("tag", "imported").put("server", host).put("server_port", port).put("uuid", uuid)
            .put("security", data.optString("scy", "auto")).put("alter_id", data.optString("aid", "0").toInt())
        if (data.optString("tls", "") == "tls") output.put("tls", JSONObject().put("enabled", true).put("server_name", data.optString("sni", host)))
        transport(mapOf("type" to data.optString("net", "tcp"), "path" to data.optString("path", "/"), "host" to data.optString("host", ""), "serviceName" to data.optString("path", "")))?.let { output.put("transport", it) }
        return Server(link, data.optString("ps", host), "vmess", host, port, output, null, null)
    }
    private fun wireGuard(text: String, name: String?, original: String?): Server {
        val parsed = WireGuardProfile.parse(text)
        val endpoint = parsed.endpoint()
        val peer = endpoint.getJSONArray("peers").getJSONObject(0)
        val awg = parsed.version != WireGuardProfile.Version.WG
        val label = name ?: parsed.fields()["Name"] ?: if (awg) "AmneziaWG" else "WireGuard / WARP"
        return Server(original ?: text, label, if (awg) "awg" else "wireguard", peer.getString("address"), peer.getInt("port"), null, endpoint, null, null, parsed.dns())
    }
    private fun tls(host: String, q: Map<String, String>, quic: Boolean): JSONObject {
        val insecure = q["insecure"] ?: q["allowInsecure"] ?: "0"
        val tls = JSONObject().put("enabled", true).put("server_name", q["sni"] ?: q["serverName"] ?: host).put("insecure", insecure in setOf("1", "true"))
        q["alpn"]?.let { tls.put("alpn", list(it)) }; val reality = q["security"] == "reality"
        if (reality) tls.put("reality", JSONObject().put("enabled", true).put("public_key", required(q["pbk"])).put("short_id", q["sid"] ?: ""))
        if (!quic && (reality || q.containsKey("fp"))) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", q["fp"] ?: "chrome"))
        return tls
    }
    private fun transport(q: Map<String, String>): JSONObject? {
        var type = q["type"] ?: q["network"] ?: "tcp"; if (type.isEmpty() || type == "tcp") return null; if (type == "splithttp") type = "xhttp"
        require(type in setOf("ws", "grpc", "httpupgrade", "xhttp")); val transport = JSONObject().put("type", type)
        if (type == "grpc") transport.put("service_name", q["serviceName"] ?: "")
        else { transport.put("path", q["path"] ?: "/"); val host = q["host"].orEmpty(); if (host.isNotEmpty()) transport.put(if (type == "xhttp") "host" else "headers", if (type == "xhttp") host else JSONObject().put("Host", host)) }
        if (type == "xhttp") add(transport, "mode", q["mode"]); return transport
    }
    private fun query(query: String?): Map<String, String> = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
        query?.split('&')?.forEach { part -> val pieces = part.split('=', limit = 2); put(decode(pieces[0]), decode(pieces.getOrNull(1))) }
    }
    private fun decode(value: String?) = if (value == null) "" else URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    private fun decode64(value: String) = CompatibilityImportFormats.decodeBase64(value)
    private fun required(value: String?): String { require(!value.isNullOrBlank()); return value }
    private fun add(objectRef: JSONObject, key: String, value: String?) { if (value != null) objectRef.put(key, value) }
    private fun list(value: String) = JSONArray(value.split(',').map { it.trim() })
}
