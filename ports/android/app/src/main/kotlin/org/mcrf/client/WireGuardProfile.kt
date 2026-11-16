package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

/** Single-peer, userspace WG/AWG codec. No shell hooks or second tunnel backend. */
object WireGuardProfile {
    enum class Version { WG, AWG_1, AWG_2, AWG_3, AWG_3_1 }
    private val baseInterface = setOf("name", "privatekey", "address", "dns", "mtu", "listenport", "workers", "udptimeout", "system")
    private val basePeer = setOf("publickey", "presharedkey", "endpoint", "allowedips", "persistentkeepalive", "reserved")
    private val awg1 = setOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")
    private val awg2 = setOf("s3", "s4", "i1", "i2", "i3", "i4", "i5")
    private val awg3 = setOf("headerprotectionkey", "contentpaddingaddition")
    private val awg31 = setOf("rekeyaftertime", "rekeytimeout", "rejectaftertime", "keepalivetimeout", "maxhandshakeattempts", "randomtrailers", "disablecookies")
    private val allAwg = awg1 + awg2 + awg3 + awg31
    private val canonical = (baseInterface + basePeer + allAwg).associateWith { key -> when(key) {
        "privatekey" -> "PrivateKey"; "publickey" -> "PublicKey"; "presharedkey" -> "PresharedKey"
        "allowedips" -> "AllowedIPs"; "persistentkeepalive" -> "PersistentKeepalive"; "listenport" -> "ListenPort"
        "dns" -> "DNS"; "mtu" -> "MTU"
        "udptimeout" -> "UDPTimeout"
        "headerprotectionkey" -> "HeaderProtectionKey"; "contentpaddingaddition" -> "ContentPaddingAddition"
        "rekeyaftertime" -> "RekeyAfterTime"; "rekeytimeout" -> "RekeyTimeout"; "rejectaftertime" -> "RejectAfterTime"
        "keepalivetimeout" -> "KeepaliveTimeout"; "maxhandshakeattempts" -> "MaxHandshakeAttempts"
        "randomtrailers" -> "RandomTrailers"; "disablecookies" -> "DisableCookies"
        else -> key.replaceFirstChar { it.uppercaseChar() }
    } }
    private val nativeNames = mapOf("headerprotectionkey" to "header_protection_key", "contentpaddingaddition" to "content_padding_addition", "rekeyaftertime" to "rekey_after_time", "rekeytimeout" to "rekey_timeout", "rejectaftertime" to "reject_after_time", "keepalivetimeout" to "keepalive_timeout", "maxhandshakeattempts" to "max_handshake_attempts", "randomtrailers" to "random_trailers", "disablecookies" to "disable_cookies")
    private fun normalized(key: String) = key.lowercase(Locale.ROOT).replace("_", "")
    private fun safe(value: String) { require(value.none { it == '\r' || it == '\n' || it.code < 32 }) { "Поле WireGuard содержит управляющие символы." } }

    class Profile internal constructor(private val values: Map<String, String>, val version: Version) {
        fun fields(): Map<String, String> = values.mapKeys { canonical.getValue(it.key) }
        fun conf(): String = buildString {
            append("[Interface]\n")
            values.filterKeys { it in baseInterface || it in allAwg }.forEach { (k,v) -> append("${canonical.getValue(k)} = $v\n") }
            append("[Peer]\n")
            values.filterKeys { it in basePeer }.forEach { (k,v) -> append("${canonical.getValue(k)} = $v\n") }
        }
        /** Contains the private key; show the same explicit share/QR warning as exporting .conf. */
        fun link(): String {
            val ep = URI("udp://${values.getValue("endpoint")}")
            val scheme = if (version == Version.WG) "wireguard" else "awg"
            val host = ep.host.removePrefix("[").removeSuffix("]")
            val authority = if (host.contains(':')) "[$host]" else host
            val query = values.filterKeys { it !in setOf("name", "endpoint", "privatekey") }.entries.joinToString("&") { (k,v) -> "${canonical.getValue(k)}=${encode(v)}" }
            return "$scheme://${encode(values.getValue("privatekey"))}@$authority:${ep.port}?$query#${encode(values["name"].orEmpty())}"
        }
        /** Flat AWG fields match the existing sing-box-lx backend, not qThrone's amnezia_wg wrapper. */
        fun endpoint(tag: String = "imported"): JSONObject {
            val ep = URI("udp://${values.getValue("endpoint")}")
            val peer = JSONObject().put("address", ep.host.removePrefix("[").removeSuffix("]")).put("port", ep.port)
                .put("public_key", values.getValue("publickey")).put("allowed_ips", JSONArray(items(values["allowedips"] ?: "0.0.0.0/0, ::/0")))
            values["presharedkey"]?.let { peer.put("pre_shared_key", it) }
            values["persistentkeepalive"]?.let { peer.put("persistent_keepalive_interval", it.toIntOrNull() ?: it) }
            values["reserved"]?.let { peer.put("reserved", JSONArray(items(it).map(String::toInt))) }
            val node = JSONObject().put("type", "wireguard").put("tag", tag).put("private_key", values.getValue("privatekey"))
                .put("address", JSONArray(items(values.getValue("address")))).put("peers", JSONArray().put(peer))
            values["mtu"]?.let { node.put("mtu", it.toInt()) }
            values["listenport"]?.let { node.put("listen_port", it.toInt()) }
            values["workers"]?.let { node.put("workers", it.toInt()) }
            values["udptimeout"]?.let { node.put("udp_timeout", it) }
            values["system"]?.let { node.put("system", false) }
            values.filterKeys { it in allAwg }.forEach { (k,v) ->
                node.put(nativeNames[k] ?: k, when {
                    k in setOf("randomtrailers", "disablecookies") -> v == "true"
                    k in setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4") -> v.toInt()
                    else -> v
                })
            }
            return node
        }
        /** Caller must apply this to the actual VPN DNS policy; endpoint JSON has no DNS field. */
        fun dns(): List<String> = values["dns"]?.let(::items).orEmpty()
        fun edited(changes: Map<String, String?>): Profile {
            val next = values.toMutableMap()
            changes.forEach { (key, value) -> val k = normalized(key); require(k in canonical) { "Неизвестное поле WireGuard." }; if (value.isNullOrBlank()) next.remove(k) else next[k] = value.trim() }
            return validate(next)
        }
        override fun toString(): String = "WireGuardProfile($version, secrets hidden)"
    }

    fun parse(source: String): Profile {
        require(source.toByteArray(Charsets.UTF_8).size <= 65536) { "Профиль WireGuard больше 64 КБ." }
        val text = source.trim().removePrefix("\uFEFF")
        if (Regex("(?i)^(wg|wireguard|awg)://").containsMatchIn(text)) return try { parseLink(text) } catch(_: java.net.URISyntaxException) { throw IllegalArgumentException("Некорректная ссылка WireGuard.") }
        val fields = linkedMapOf<String,String>(); var section = ""; var interfaces = 0; var peers = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isNotEmpty() && !line.startsWith('#') && !line.startsWith(';')) {
                if (line.startsWith('[')) {
                    section = line.lowercase(Locale.ROOT)
                    when(section) { "[interface]" -> interfaces++; "[peer]" -> peers++; else -> error("Неизвестная секция WireGuard.") }
                    require(interfaces == 1 && peers <= 1) { "Импортируй WireGuard-пиров отдельно." }
                } else {
                    val parts = line.split('=', limit = 2); require(parts.size == 2) { "Ожидается поле WireGuard = значение." }
                    val key = normalized(parts[0].trim()); val value = parts[1].trim()
                    require(key in if(section == "[interface]") baseInterface + allAwg else if(section == "[peer]") basePeer else emptySet()) { "Неизвестное поле или неверная секция WireGuard: ${parts[0].take(40)}." }
                    require(fields.put(key, value) == null) { "Повторяющееся поле WireGuard: ${canonical[key]}." }
                }
            }
        }
        require(interfaces == 1 && peers == 1) { "Нужны секции Interface и один Peer." }
        return validate(fields)
    }
    private fun parseLink(text: String): Profile {
        val uri = URI(text); require(uri.port in 1..65535 && !uri.host.isNullOrBlank()) { "Нужны адрес и порт WireGuard." }
        require(uri.rawPath.isNullOrEmpty()) { "Ссылка WireGuard содержит лишний путь." }
        val fields = linkedMapOf<String,String>(); var enabled: Boolean? = null
        uri.rawQuery?.split('&')?.filter(String::isNotEmpty)?.forEach { item ->
            val parts = item.split('=', limit=2); require(parts.size == 2)
            var key = normalized(decode(parts[0])); var value = decode(parts[1])
            if(key == "enableamnezia") { require(enabled == null); require(value in setOf("true","false")); enabled=value=="true";return@forEach }
            key = when(key) { "localaddress", "ip" -> "address"; "psk" -> "presharedkey"; "workercount" -> "workers"; "usesysteminterface" -> "system"; else -> key }
            if (normalized(decode(parts[0])) == "localaddress") value = value.replace('-', ',')
            require(key in canonical && key !in setOf("name", "endpoint")) { "Неизвестный параметр ссылки WireGuard." }
            require(fields.put(key, value) == null) { "Повторяющийся параметр ссылки WireGuard." }
        }
        uri.rawUserInfo?.takeIf(String::isNotEmpty)?.let { require(fields.put("privatekey", decode(it)) == null) { "Два приватных ключа в ссылке." } }
        val host = uri.host.removePrefix("[").removeSuffix("]")
        fields["endpoint"] = "${if(host.contains(':')) "[$host]" else host}:${uri.port}"
        uri.rawFragment?.takeIf(String::isNotEmpty)?.let { fields["name"] = decode(it) }
        val result = validate(fields)
        enabled?.let { require(it == (result.version != Version.WG)) { "Флаг AmneziaWG не соответствует параметрам обфускации." } }
        require(!uri.scheme.equals("awg", true) || result.version != Version.WG) { "Ссылка AWG не содержит параметров обфускации." }
        return result
    }
    fun validateEndpoint(node: JSONObject) {
        fromEndpoint(node) // Converts and validates all supported fields, rejecting unknown endpoint options.
    }
    fun fromEndpoint(node: JSONObject, name: String = "", dns: List<String> = emptyList()): Profile {
        require(node.optString("type") == "wireguard")
        val allowed = setOf("type", "tag", "private_key", "address", "peers", "mtu", "listen_port", "workers", "udp_timeout", "system") + allAwg.map { nativeNames[it] ?: it }
        require(node.keys().asSequence().all { it in allowed }) { "Профиль содержит неподдерживаемые параметры WireGuard." }
        val peers = node.getJSONArray("peers"); require(peers.length() == 1) { "Импортируй WireGuard-пиров отдельно." }
        val peer = peers.getJSONObject(0)
        require(peer.keys().asSequence().all { it in setOf("address", "port", "public_key", "allowed_ips", "pre_shared_key", "persistent_keepalive_interval", "reserved") }) { "Неизвестное поле пира WireGuard." }
        val host = peer.getString("address"); val fields = linkedMapOf("privatekey" to node.getString("private_key"), "publickey" to peer.getString("public_key"), "address" to arrayText(node.getJSONArray("address")), "endpoint" to "${if(host.contains(':')) "[$host]" else host}:${peer.get("port")}")
        if(name.isNotEmpty()) fields["name"] = name
        if(dns.isNotEmpty()) fields["dns"] = dns.joinToString(", ")
        node.keys().forEach { key -> if(normalized(key) in allAwg || key in setOf("mtu", "listen_port", "workers", "udp_timeout", "system")) fields[normalized(key)] = node.get(key).toString() }
        mapOf("allowed_ips" to "allowedips", "pre_shared_key" to "presharedkey", "persistent_keepalive_interval" to "persistentkeepalive", "reserved" to "reserved").forEach { (native, key) -> if(peer.has(native)) fields[key] = if(peer.get(native) is JSONArray) arrayText(peer.getJSONArray(native)) else peer.get(native).toString() }
        return validate(fields)
    }
    private fun validate(input: Map<String,String>): Profile {
        val values = LinkedHashMap(input)
        values.values.forEach(::safe)
        listOf("privatekey", "publickey").forEach { key(values[it].orEmpty()) }
        values["presharedkey"]?.let(::key)
        require(values["name"].orEmpty().length <= 256) { "Название WireGuard длиннее 256 символов." }
        val addresses = items(values.getValue("address")); require(addresses.size in 1..16)
        values["address"] = addresses.joinToString(", ") { cidr(it, true) }
        values["allowedips"]?.let { require(items(it).size in 1..256); values["allowedips"] = items(it).joinToString(", ") { ip -> cidr(ip, false) } }
        val ep = URI("udp://${values.getValue("endpoint")}")
        require(!ep.host.isNullOrBlank() && ep.port in 1..65535 && ep.rawUserInfo == null && ep.rawPath.isNullOrEmpty() && ep.rawQuery == null && ep.rawFragment == null) { "Endpoint: домен/IP и порт 1–65535." }
        values["dns"]?.let { dns -> require(items(dns).size in 1..8 && items(dns).all { literal(it) != null }) { "DNS: IP-адреса через запятую; поисковые домены не поддерживаются." } }
        values["mtu"]?.let { require(it.toIntOrNull() in 576..9000) { "MTU: 576–9000." } }
        values["listenport"]?.let { require(it.toIntOrNull() in 0..65535) { "ListenPort: 0–65535." } }
        values["workers"]?.let { require(it.toIntOrNull() in 0..256) { "Workers: 0–256; 0 — автоматически." } }
        values["udptimeout"]?.let { require(it.matches(Regex("(?:[0-9]+(?:\\.[0-9]+)?(?:ms|s|m|h))+"))) { "UDPTimeout: длительность, например 30s или 5m." } }
        values["system"]?.let { require(it.equals("false",true) || it == "0") { "Системный интерфейс WireGuard недоступен в Android VPN MCRF. Используй userspace-режим." }; values["system"]="false" }
        values["persistentkeepalive"]?.let { range(it, 65535L) }
        values["reserved"]?.let { require(items(it).size == 3 && items(it).all { b -> b.toIntOrNull() in 0..255 }) { "Reserved: три байта 0–255." } }
        val present = values.keys.intersect(allAwg)
        (present intersect setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")).forEach { require(values.getValue(it).toIntOrNull() in 0..65535) { "${canonical[it]}: целое число 0–65535." } }
        if(values.containsKey("jmin") && values.containsKey("jmax")) require(values.getValue("jmin").toInt() <= values.getValue("jmax").toInt()) { "Jmin должен быть не больше Jmax." }
        (present intersect setOf("h1", "h2", "h3", "h4")).forEach { range(values.getValue(it), 0xffffffffL) }
        (present intersect setOf("contentpaddingaddition", "rekeyaftertime", "rekeytimeout", "rejectaftertime", "keepalivetimeout", "maxhandshakeattempts")).forEach { range(values.getValue(it), 0x7fffffffL) }
        values["headerprotectionkey"]?.let {
            key(it)
            require(Base64.getDecoder().decode(it).any { byte -> byte != 0.toByte() }) { "HeaderProtectionKey не может быть нулевым." }
            require((1..4).all { index -> (values["s$index"]?.toIntOrNull() ?: 0) >= 12 }) { "Для HeaderProtectionKey каждый S1–S4 должен быть не меньше 12 байт." }
        }
        (present intersect setOf("randomtrailers", "disablecookies")).forEach { k -> values[k] = when(values.getValue(k).lowercase(Locale.ROOT)) { "1", "true", "yes", "on" -> "true"; "0", "false", "no", "off" -> "false"; else -> error("${canonical[k]}: true или false.") } }
        (present intersect setOf("i1", "i2", "i3", "i4", "i5")).forEach { k -> require(values.getValue(k).length in 1..4096 && values.getValue(k).startsWith('<') && values.getValue(k).endsWith('>')) { "$k: ожидается цепочка CPS; точную грамматику проверяет установленный движок." } }
        val version = when { present.any { it in awg31 } -> Version.AWG_3_1; present.any { it in awg3 } || values["persistentkeepalive"].orEmpty().contains('-') -> Version.AWG_3; present.any { it in awg2 } || present.any { it in setOf("h1", "h2", "h3", "h4") && values.getValue(it).contains('-') } -> Version.AWG_2; present.isNotEmpty() -> Version.AWG_1; else -> Version.WG }
        return Profile(values, version)
    }
    private fun key(value: String) { require(value.matches(Regex("[A-Za-z0-9+/]{43}=")) && Base64.getDecoder().decode(value).size == 32 && Base64.getEncoder().encodeToString(Base64.getDecoder().decode(value)) == value) { "Ключ WireGuard: 32 байта в Base64." } }
    private fun range(value: String, max: Long) { val parts = value.split('-'); require(parts.size in 1..2 && parts.all { it.matches(Regex("[0-9]+")) && it.toLongOrNull() in 0L..max } && (parts.size == 1 || parts[0].toLong() <= parts[1].toLong())) { "Некорректное число или диапазон AWG." } }
    private fun items(value: String) = value.split(',').map(String::trim)
    private fun arrayText(array: JSONArray) = (0 until array.length()).joinToString(", ") { array.get(it).toString() }
    private fun cidr(value: String, hostDefault: Boolean): String {
        val parts = value.split('/'); require(parts.size in 1..2)
        val ip = literal(parts[0]) ?: error("Ожидается IP-адрес WireGuard.")
        val max = if(ip is Inet6Address) 128 else 32
        val prefix = if(parts.size == 1 && hostDefault) max else parts.getOrNull(1)?.toIntOrNull()
        require(prefix != null && prefix in 0..max) { "Некорректная маска WireGuard." }
        return "${parts[0]}/$prefix"
    }
    private fun literal(value: String): InetAddress? = try {
        when { value.contains(':') && value.matches(Regex("[0-9a-fA-F:.]+")) -> InetAddress.getByName(value).takeIf { it is Inet6Address }
            value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && value.split('.').all { it.toInt() in 0..255 } -> InetAddress.getByAddress(value.split('.').map { it.toInt().toByte() }.toByteArray())
            else -> null }
    } catch (_: Exception) { null }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
}
