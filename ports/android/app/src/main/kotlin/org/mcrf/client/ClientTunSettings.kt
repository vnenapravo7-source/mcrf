package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket

data class ClientTunSettings(
    val mtu: Int = 1400, val ipv6: Boolean = true,
    val address4: String = DEFAULT_ADDRESS4, val address6: String = DEFAULT_ADDRESS6,
    val bypassPrivate: Boolean = false, val privateRanges: String = DEFAULT_PRIVATE,
    val bypassDirectIps: Boolean = false, val mixedEnabled: Boolean = true,
    val randomPort: Boolean = false, val mixedPort: Int = 10808,
    val httpProxy: Boolean = false, val httpBypass: String = "",
    val shareLan: Boolean = false, val lanPort: Int = 10809,
    val dnsPort: Int = 0, val customInbounds: String = "",
    val ipv4Compatibility: Boolean = false
) {
    fun validate(auth: JSONObject? = null): ClientTunSettings {
        require(mtu in (if (ipv6) 1280 else 576)..9000) { "MTU: ${if (ipv6) 1280 else 576}–9000." }
        require(ClientTunCidr.parse(address4, false).bits == 32 && !java.net.InetAddress.getByName(address4.substringBefore('/')).let { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }) { "Нужен unicast TUN IPv4 CIDR, не loopback/0.0.0.0." }
        require(ClientTunCidr.parse(address6, false).bits == 128 && !java.net.InetAddress.getByName(address6.substringBefore('/')).let { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }) { "Нужен unicast TUN IPv6 CIDR, не loopback/::." }
        val privateCidrs = ClientTunCidr.lines(privateRanges)
        require(!bypassPrivate || privateCidrs.isNotEmpty()) { "Для обхода частных сетей нужен хотя бы один CIDR; пустой список не должен направлять весь трафик напрямую." }
        require(mixedPort in 1024..65535 && lanPort in 1024..65535 && dnsPort in 0..65535 && dnsPort !in 1..1023) { "Порт: 1024–65535; DNS 0 выключен." }
        require(!httpProxy || mixedEnabled) { "Системному HTTP-прокси нужен mixed listener." }
        if (auth != null) {
            LocalProxyConfig.validate(auth)
            require(!httpProxy || !auth.getBoolean("enabled")) { "Android не передаёт логин и пароль системного прокси. Отключите HTTP handoff или авторизацию." }
            require(!shareLan || auth.getBoolean("enabled")) { "Раздача в LAN требует авторизацию." }
        }
        httpBypassLines()
        val ports = mutableSetOf<Int>()
        if (mixedEnabled && !randomPort) ports.add(mixedPort)
        if (shareLan) require(ports.add(lanPort)) { "Порт LAN совпадает с другим listener." }
        if (dnsPort > 0) require(ports.add(dnsPort)) { "Порт DNS совпадает с другим listener." }
        customListeners().forEach { require(ports.add(it.getInt("listen_port"))) { "Порты custom inbound должны отличаться от остальных listener." } }
        return this
    }
    fun httpBypassLines(): List<String> = httpBypass.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList().also { lines ->
        require(lines.size <= 256 && lines.all { it.length <= 253 && it.matches(Regex("[a-zA-Z0-9*._-]+")) }) { "HTTP bypass: по одному домену/маске на строку." }
    }
    fun customListeners(): List<JSONObject> {
        if (customInbounds.isBlank()) return emptyList()
        require(customInbounds.length <= 65536) { "Custom inbound больше 64 КБ." }
        val array = JSONArray(customInbounds); require(array.length() <= 16) { "До 16 custom inbound." }
        val tags = hashSetOf("tun-in", "local-proxy", "mcrf-lan", "mcrf-dns")
        return (0 until array.length()).map { i -> array.getJSONObject(i).also { item ->
            require(listOf("type","tag","listen").all { item.opt(it) is String } && item.opt("listen_port") is Int) { "Custom inbound: type/tag/listen должны быть строками, listen_port — целым числом." }
            require(item.optString("type") in setOf("mixed", "socks", "http", "direct")) { "Custom inbound: mixed/socks/http/direct; остальные требуют отдельной интеграции." }
            require(item.optString("listen") in setOf("127.0.0.1", "::1")) { "Custom inbound разрешён только на loopback; для LAN используйте защищённую раздачу." }
            require(item.optInt("listen_port") in 1024..65535) { "Неверный порт custom inbound." }
            require(item.optString("tag").isNotBlank() && tags.add(item.getString("tag"))) { "Теги inbound должны отличаться." }
            require(!item.optBoolean("set_system_proxy")) { "Используйте настройку Android HTTP proxy." }
            val allowed = setOf("type","tag","listen","listen_port") + if(item.getString("type") == "direct") setOf("network","override_address","override_port") else setOf("users")
            require(item.keys().asSequence().all { it in allowed }) { "Неизвестное поле custom inbound; TLS и остальные расширения добавляются только после интеграции с ядром." }
            require(!item.has("users") || item.opt("users") is JSONArray) { "Custom inbound users должен быть JSON-массивом." }
            item.optJSONArray("users")?.let { users -> repeat(users.length()) { index -> val user = users.getJSONObject(index); LocalProxyConfig.validate(JSONObject(user.toString()).put("enabled",true)) } }
        } }
    }
    /** Select once per start; close the reservation before libbox starts. Native bind failures must surface. */
    fun choosePort(): Int = if (randomPort) ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort } else mixedPort
    fun json(): JSONObject = JSONObject().put("mtu", mtu).put("ipv6", ipv6).put("address4", address4).put("address6", address6)
        .put("bypassPrivate", bypassPrivate).put("privateRanges", privateRanges).put("bypassDirectIps", bypassDirectIps)
        .put("mixedEnabled", mixedEnabled).put("randomPort", randomPort).put("mixedPort", mixedPort).put("httpProxy", httpProxy)
        .put("httpBypass", httpBypass).put("shareLan", shareLan).put("lanPort", lanPort).put("dnsPort", dnsPort).put("customInbounds", customInbounds)
        .put("ipv4Compatibility", ipv4Compatibility)
    companion object {
        const val KEY = "clientTun"
        const val DEFAULT_ADDRESS4 = "172.19.0.1/30"
        const val DEFAULT_ADDRESS6 = "fdfe:dcba:9876::1/126"
        const val DEFAULT_PRIVATE = "10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n169.254.0.0/16\nfc00::/7\nfe80::/10"
        fun fromJson(j: JSONObject): ClientTunSettings { CoreTuningJson.validateTypes(j,ClientTunSettings().json()); return ClientTunSettings(j.optInt("mtu",1400),j.optBoolean("ipv6",true),j.optString("address4",DEFAULT_ADDRESS4),j.optString("address6",DEFAULT_ADDRESS6),j.optBoolean("bypassPrivate"),j.optString("privateRanges",DEFAULT_PRIVATE),j.optBoolean("bypassDirectIps"),j.optBoolean("mixedEnabled",true),j.optBoolean("randomPort"),j.optInt("mixedPort",10808),j.optBoolean("httpProxy"),j.optString("httpBypass"),j.optBoolean("shareLan"),j.optInt("lanPort",10809),j.optInt("dnsPort"),j.optString("customInbounds"),j.optBoolean("ipv4Compatibility",false)).validate() }
    }
}
