package org.mcrf.client

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Official OLC URI v1; DNSTT explicitly names the domain, server key, and resolver. */
object CarrierProfile {
    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    @JvmStatic fun olc(link: String): ServerImport.Server {
        require(link.length <= 16384)
        val text = link.substring(9); val q = text.indexOf('?'); val at = text.indexOf('@', q + 1)
        val hash = text.lastIndexOf('#'); val name = text.indexOf('$', hash + 1)
        require(q >= 1 && at > q && hash > at)
        var spec = text.substring(q + 1, at); val options = JSONObject(); val bracket = spec.indexOf('<')
        if (bracket >= 0) {
            require(spec.endsWith('>'))
            spec.substring(bracket + 1, spec.length - 1).split('&').forEach { item ->
                val pair = item.split('=', limit = 2); require(pair.size == 2 && !options.has(pair[0]))
                if (pair[0] !in setOf("video-bitrate", "video-hw")) options.put(pair[0], decode(pair[1]))
            }
            spec = spec.substring(0, bracket)
        }
        val data = JSONObject().put("type", "olcrtc").put("provider", text.substring(0, q)).put("transport", spec)
            .put("room", decode(text.substring(at + 1, hash))).put("key", text.substring(hash + 1, if (name < 0) text.length else name))
            .put("name", if (name < 0) "olcRTC" else decode(text.substring(name + 1))).put("options", options)
        return fromJson(link, data)
    }
    @JvmStatic fun dnstt(link: String): ServerImport.Server {
        require(link.length <= 16384)
        val uri = URI(link)
        val data = JSONObject().put("type", "dnstt").put("domain", uri.host).put("key", decode(uri.rawUserInfo.orEmpty()))
            .put("name", decode(uri.rawFragment ?: "DNSTT"))
        require(uri.port == -1 && uri.path.isNullOrEmpty())
        val fields = hashSetOf<String>()
        uri.rawQuery?.split('&')?.forEach { field ->
            val pair = field.split('=', limit = 2); val key = decode(pair[0])
            require(pair.size == 2 && key in setOf("doh", "dot", "udp", "username", "password") && fields.add(key))
            data.put(key, decode(pair[1]))
        }
        return fromJson(link, data)
    }
    @JvmStatic fun fromJson(source: String, raw: JSONObject): ServerImport.Server {
        if(S3BypassProfile.isProfile(raw))return S3BypassProfile.fromJson(source,raw)
        if (raw.optString("type") == "openflux") return OpenFluxProfile.fromJson(source, raw)
        if (raw.optString("type") in SwgProfile.TYPES) return SwgProfile.fromJson(source, raw)
        val data = JSONObject(raw.toString()); val type = data.getString("type"); val key = data.getString("key")
        val name = data.optString("name", if (type == "olcrtc") "olcRTC" else "DNSTT")
        require(source.toByteArray(Charsets.UTF_8).size <= 65536) { "Профиль движка больше 64 КБ." }
        require(key.matches(Regex("[0-9a-fA-F]{64}"))) { "Ключ должен содержать 64 шестнадцатеричных символа." }
        require(name.length in 1..256 && name.none(Char::isISOControl)) { "Название: 1–256 символов без управляющих знаков." }
        val host = when (type) {
            "olcrtc" -> {
                val provider = data.getString("provider"); val transport = data.getString("transport"); val room = data.getString("room")
                require(provider in setOf("jitsi", "telemost", "wbstream") && transport in setOf("datachannel", "vp8channel", "seichannel", "videochannel")) { "Проверь провайдер и транспорт OLC." }
                require(room.isNotBlank() && room.length <= 8192 && room.none(Char::isISOControl)) { "Укажи комнату OLC без управляющих знаков, до 8192 символов." }
                require(!(provider == "telemost" && transport in setOf("datachannel", "seichannel")))
                require(!(provider == "wbstream" && transport == "datachannel" && data.optString("token").isEmpty()))
                val address = if (provider == "jitsi") URI(if (room.startsWith("https://")) room else "https://$room") else null
                if (address != null) require(address.scheme == "https" && address.host != null && address.userInfo == null && address.path.length >= 2 && address.port != 0 && address.port <= 65535) { "Jitsi: HTTPS-ссылка с доменом и путём комнаты." }
                if (!data.has("dns")) data.put("dns", "8.8.8.8:53")
                numericResolver(data.getString("dns"))
                data.optJSONObject("options")?.let(::validateOptions)
                address?.host ?: provider
            }
            "dnstt" -> {
                val domain = data.getString("domain")
                require(domain.length <= 100 && domain.matches(Regex("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+"))) { "Укажи делегированный домен DNSTT до 100 символов." }
                val resolvers = listOf("doh", "dot", "udp").count { data.has(it) && data.getString(it).isNotEmpty() }
                if (resolvers == 0) {
                    require(listOf("doh", "dot", "udp").none(data::has)) { "Адрес резолвера DNSTT не может быть пустым." }
                    data.put("doh", "https://cloudflare-dns.com/dns-query")
                } else require(resolvers == 1) { "Укажи ровно один резолвер DNSTT: DoH, DoT или UDP." }
                if (data.has("doh")) { val uri = URI(data.getString("doh")); require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null && uri.port != 0 && uri.port <= 65535) { "DoH: корректная HTTPS-ссылка без логина и фрагмента." } }
                if (data.has("dot")) resolver(data.getString("dot"))
                if (data.has("udp")) numericResolver(data.getString("udp"))
                val user = data.optString("username"); val password = data.optString("password")
                require(user.isEmpty() == password.isEmpty() && user.toByteArray(Charsets.UTF_8).size <= 255 && password.toByteArray(Charsets.UTF_8).size <= 255 && user.none(Char::isISOControl) && password.none(Char::isISOControl)) { "Логин и пароль задаются вместе: каждый до 255 байт UTF-8, без управляющих знаков." }
                domain
            }
            else -> throw IllegalArgumentException()
        }
        return ServerImport.Server(source, name, type, host, 443, null, null, null, data)
    }
    private fun validateOptions(options: JSONObject) {
        val maximum = mapOf("vp8-fps" to 120, "vp8-batch" to 64, "fps" to 120, "batch" to 64, "frag" to 65535, "ack-ms" to 60000,
            "video-w" to 4096, "video-h" to 4096, "video-fps" to 120, "video-qr-size" to 65535, "video-tile-module" to 270, "video-tile-rs" to 200)
        options.keys().forEach { option ->
            val value = options.get(option).toString()
            when (option) {
                "video-codec" -> require(value in setOf("qrcode", "tile"))
                "video-qr-recovery" -> require(value in setOf("low", "medium", "high", "highest"))
                else -> { val number = value.toInt(); val min = if (option in setOf("video-qr-size", "video-tile-rs")) 0 else 1
                    require(maximum.containsKey(option) && number in min..maximum.getValue(option)) }
            }
        }
        if (options.optString("video-codec") == "tile") require(options.optString("video-w") == "1080" && options.optString("video-h") == "1080")
    }
    private fun resolver(address: String): URI = URI("tcp://$address").also {
        require(it.host != null && it.port in 1..65535 && it.userInfo == null && it.path.isEmpty() && it.query == null && it.fragment == null)
    }
    private fun numericResolver(address: String) {
        val host = resolver(address).host.replace("[", "").replace("]", "")
        require(RouteRules.literal(host) != null)
    }
}
