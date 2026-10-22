package org.mcrf.client

import org.json.JSONObject
import java.net.URI

/** Local overlays keep subscription identity and its upstream source unchanged. */
object ConnectionEdits {
    fun captcha(entry:ServerImport.Server,automatic:Boolean):JSONObject {
        require(entry.protocol in SwgProfile.TYPES && entry.carrier!=null) {"Для этого подключения капча не используется."}
        return snapshot(entry).also {options->
            val carrier=options.getJSONObject("carrier")
            carrier.put("captcha_mode",CarrierCaptchaMode.select(carrier.optString("captcha_mode","auto"),automatic))
            apply(entry,options)
        }
    }
    @JvmStatic fun snapshot(entry: ServerImport.Server): JSONObject = JSONObject().put("name", entry.name).apply {
        entry.outbound?.let { put("outbound", JSONObject(it.toString())) }
        entry.endpoint?.let { put("endpoint", JSONObject(it.toString())) }
        entry.configuration?.let { put("configuration", JSONObject(it.toString())) }
        entry.carrier?.let { put("carrier", JSONObject(it.toString())) }
        if (entry.endpoint != null) put("profileDns", org.json.JSONArray(entry.profileDns))
    }
    @JvmStatic fun apply(entry: ServerImport.Server, options: JSONObject?): ServerImport.Server {
        if (options == null) return entry
        require(options.toString().toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES) { "Настройки подключения больше 4 МБ." }
        require(options.keys().asSequence().all { it in setOf("name", "outbound", "endpoint", "configuration", "carrier", "profileDns", "csqttVersion") }) { "Неизвестные настройки подключения." }
        val name = options.optString("name", entry.name).trim()
        require(name.isNotEmpty() && name.length <= 256 && name.none(Char::isISOControl)) { "Укажите название до 256 символов." }
        fun node(kind: String, original: JSONObject?): JSONObject? {
            if (!options.has(kind)) return original?.let { JSONObject(it.toString()) }
            require(original != null) { "Нельзя изменить тип подключения." }
            return JSONObject(options.getJSONObject(kind).toString()).also { changed ->
                require(changed.optString("type") == original.optString("type")) { "Нельзя изменить протокол подключения." }
            }
        }
        val output = node("outbound", entry.outbound)
        val endpoint = node("endpoint", entry.endpoint)
        var carrier = node("carrier", entry.carrier)
        if(options.has("csqttVersion")){
            require(options.get("csqttVersion") is String&&options.getString("csqttVersion") in CsqttGenerations.legacy.map {it.id}){"Неизвестная версия CSQTT."}
            val version=options.getString("csqttVersion")
            val raw=requireNotNull(carrier){"Версия доступна только для CSQTT."}
            require(raw.optString("type")=="csqtt"&&(!raw.has("csqtt_generation")||CsqttGenerations.family(raw).id==version)){
                "Поколение сервера в подписке изменилось. Перепроверьте его версию; другой движок автоматически не подставлен."
            }
            carrier=JSONObject(raw.toString()).put("csqtt_generation",version)
        }
        val config = if (options.has("configuration")) {
            require(entry.configuration != null) { "Нельзя заменить ссылку произвольным профилем." }
            JSONObject(options.getJSONObject("configuration").toString()).also { ProfileConfig.build(it.toString(), true, 10808) }
        } else entry.configuration
        var host = entry.host; var port = entry.port
        val profileDns = if (options.has("profileDns")) {
            require(entry.endpoint != null) { "DNS профиля доступен только для WireGuard." }
            options.getJSONArray("profileDns").let { values -> (0 until values.length()).map { values.getString(it) } }
        } else entry.profileDns
        endpoint?.let { val parsed = WireGuardProfile.fromEndpoint(it, name, profileDns); val peer = parsed.endpoint().getJSONArray("peers").getJSONObject(0); host = peer.getString("address"); port = peer.getInt("port") }
        carrier?.let { val parsed = CarrierProfile.fromJson(entry.original, JSONObject(it.toString()).put("name", name)); host = parsed.host; port = parsed.port; carrier = parsed.carrier }
        output?.let { value ->
            host = value.getString("server"); port = value.getInt("server_port")
            require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/@\\\r\n" } && port in 1..65535) { "Проверь адрес сервера и порт." }
            require(URI("tcp://${if (host.contains(':')) "[$host]" else host}:$port").host != null) { "Некорректный адрес сервера." }
            val mux = value.optJSONObject("multiplex")
            if (mux?.optBoolean("enabled") == true) {
                require(value.optString("type") in setOf("vless", "vmess", "trojan", "shadowsocks") && value.optString("flow").isEmpty()) { "MUX недоступен для этого протокола или режима Vision." }
                require(mux.optString("protocol", "h2mux") in setOf("h2mux", "smux", "yamux")) { "Неизвестный протокол MUX." }
                val maximum = mux.optInt("max_connections", 0); val minimum = mux.optInt("min_streams", 0); val streams = mux.optInt("max_streams", 0)
                require(maximum in 0..64 && minimum in 0..256 && streams in 0..256 && !(streams > 0 && (maximum > 0 || minimum > 0))) { "Предел потоков MUX нельзя сочетать с числом соединений и минимальным числом потоков." }
            }
            val tls = value.optJSONObject("tls")
            if (tls?.optBoolean("fragment") == true || tls?.optBoolean("record_fragment") == true) require(value.optString("type") !in setOf("hysteria", "hysteria2", "tuic") && tls.optBoolean("enabled")) { "Фрагментация TLS доступна только для TCP/TLS." }
        }
        return ServerImport.Server(entry.original, name, entry.protocol, host, port, output, endpoint, config, carrier, profileDns)
    }
    /** Edits must not silently disappear from a share. Export a standard standalone core profile. */
    @JvmStatic fun share(entry: ServerImport.Server, edited: Boolean): String = if (!edited) entry.original
        else if (entry.endpoint != null) WireGuardProfile.fromEndpoint(entry.endpoint, entry.name, entry.profileDns).conf()
        else if (entry.protocol == "openflux" && entry.carrier != null) OpenFluxProfile.share(JSONObject(entry.carrier.toString()).put("name", entry.name))
        else if (entry.protocol in SwgProfile.TYPES && entry.carrier != null) SwgProfile.share(JSONObject(entry.carrier.toString()).put("name", entry.name))
        else if (entry.carrier != null) JSONObject(entry.carrier.toString()).put("name", entry.name).toString()
        else ServerImport.build(listOf(entry), entry.id, false, 60, 50, "Cloudflare").put("mcrf_name", entry.name).toString()
}
