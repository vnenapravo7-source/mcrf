package org.mcrf.client

import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.Collections
import java.util.Locale

/** Preparation only: does not enable the optional backend or bypass its runtime gates. */
object CompactWireGuardPlan {
    class Plan internal constructor(
        val mtu: Int,
        val addresses: List<String>,
        val routes: List<String>,
        val dns: List<String>,
        private val configuration: String
    ) {
        /** Contains secrets. Pass directly to the isolated backend; never log or persist it. */
        fun secretConfiguration(): String = configuration
        override fun toString(): String = "CompactWireGuardPlan(redacted)"
    }

    private val deviceFields = mapOf(
        "jc" to "jc", "jmin" to "jmin", "jmax" to "jmax",
        "s1" to "s1", "s2" to "s2", "s3" to "s3", "s4" to "s4",
        "h1" to "h1", "h2" to "h2", "h3" to "h3", "h4" to "h4",
        "i1" to "i1", "i2" to "i2", "i3" to "i3", "i4" to "i4", "i5" to "i5",
        "headerprotectionkey" to "header_protection_key",
        "contentpaddingaddition" to "content_padding_addition",
        "rekeyaftertime" to "rekey_after_time", "rekeytimeout" to "rekey_timeout",
        "rejectaftertime" to "reject_after_time", "keepalivetimeout" to "keepalive_timeout",
        "maxhandshakeattempts" to "max_handshake_attempts",
        "randomtrailers" to "random_trailers", "disablecookies" to "disable_cookies"
    )

    /** resolvedEndpointIp must come from the host's explicit physical-network resolver, not OS DNS. */
    fun prepare(profile: WireGuardProfile.Profile, resolvedEndpointIp: String): Plan {
        // Revalidate even an internally constructed Profile; never interpolate untrusted UAPI lines.
        val f = WireGuardProfile.parse(profile.conf()).fields().mapKeys { it.key.lowercase(Locale.ROOT) }
        require(f.keys.none { it in setOf("workers", "udptimeout", "reserved") }) {
            "Компактный WG пока не поддерживает Workers, UDPTimeout и Reserved; параметры не отброшены."
        }
        val known = setOf("name", "privatekey", "publickey", "presharedkey", "address", "allowedips",
            "dns", "mtu", "listenport", "endpoint", "persistentkeepalive", "system") + deviceFields.keys
        require(f.keys.all { it in known }) { "Неизвестный параметр компактного WG." }
        val mtu = f["mtu"]?.toInt() ?: 1420
        require(mtu in 1280..9000) { "Компактный WG пока требует MTU 1280–9000." }
        val ip = literal(resolvedEndpointIp)
        val endpoint = URI("udp://${f.getValue("endpoint")}")
        val original = endpoint.host.removePrefix("[").removeSuffix("]")
        if (isLiteral(original)) require(literal(original).contentEquals(ip)) { "IP сервера не совпадает с профилем." }
        val authority = if (resolvedEndpointIp.contains(':')) "[$resolvedEndpointIp]" else resolvedEndpointIp
        val routes = split(f["allowedips"] ?: "0.0.0.0/0, ::/0")
        val uapi = buildString {
            append("private_key=${hex(f.getValue("privatekey"))}\n")
            f["listenport"]?.let { append("listen_port=$it\n") }
            deviceFields.forEach { (field, native) -> f[field]?.let {
                append("$native=${if (field == "headerprotectionkey") hex(it) else it}\n")
            } }
            append("replace_peers=true\npublic_key=${hex(f.getValue("publickey"))}\n")
            f["presharedkey"]?.let { append("preshared_key=${hex(it)}\n") }
            append("endpoint=$authority:${endpoint.port}\nreplace_allowed_ips=true\n")
            routes.forEach { append("allowed_ip=$it\n") }
            f["persistentkeepalive"]?.let { append("persistent_keepalive_interval=$it\n") }
        }
        require(uapi.toByteArray(Charsets.UTF_8).size <= 65536) { "Конфигурация компактного WG слишком велика." }
        return Plan(mtu, split(f.getValue("address")), routes, split(f["dns"].orEmpty()), uapi)
    }

    /** Worker-local immutable peer narrowing. NEVER edits the stored profile,
     * widens original AllowedIPs, or logs/persists the secret UAPI. Empty peer
     * ranges mean no destination is admitted, not an implicit default route. */
    fun withPeerRoutes(original:Plan,peerRoutes:List<String>):Plan {
        require(peerRoutes.size<=8192) {"PACKET_PEER_ROUTE_LIMIT"}
        val allowed=original.routes.map {ClientTunCidr.parse(it)}
        val peer=peerRoutes.map {ClientTunCidr.parse(it)}.distinct()
        require(peer.all {range->allowed.any {base->base.bits==range.bits&&base.number<=range.number&&base.end>=range.end}}) {"PACKET_PEER_ROUTE_WIDENED"}
        val canonical=Collections.unmodifiableList(peer.map {it.toString()})
        var declarations=0
        val text=buildString {
            original.secretConfiguration().lineSequence().filter {it.isNotEmpty()}.forEach {line->
                if(line.startsWith("allowed_ip="))return@forEach
                append(line).append('\n')
                if(line=="replace_allowed_ips=true"){
                    declarations++;canonical.forEach {append("allowed_ip=").append(it).append('\n')}
                }
            }
        }
        require(declarations==1&&text.toByteArray(Charsets.UTF_8).size<=65536) {"PACKET_PEER_CONFIGURATION_BOUND"}
        return Plan(original.mtu,original.addresses,canonical,original.dns,text)
    }

    private fun hex(key: String) = Base64.getDecoder().decode(key).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun split(value: String): List<String> = Collections.unmodifiableList(
        if (value.isBlank()) emptyList() else value.split(',').map(String::trim))
    private fun isLiteral(value: String) = value.contains(':') || value.matches(Regex("[0-9.]+"))
    private fun literal(value: String): ByteArray {
        require(value.isNotBlank() && value.length <= 45 && (value.contains(':') || value.contains('.')) && value.matches(Regex("[0-9a-fA-F:.]+"))) {
            "Требуется проверенный числовой IP сервера."
        }
        if (!value.contains(':')) require(value.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }) {
            "Некорректный IPv4 сервера."
        }
        return InetAddress.getByName(value).address
    }
}
