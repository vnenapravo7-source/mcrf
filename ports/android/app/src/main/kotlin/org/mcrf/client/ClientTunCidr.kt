package org.mcrf.client

import java.math.BigInteger
import java.net.InetAddress

/** Numeric literals only: validating an editor never performs DNS queries. */
@ConsistentCopyVisibility
data class ClientTunCidr private constructor(val number: BigInteger, val prefix: Int, val bits: Int) {
    val end: BigInteger get() = number.add(BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE))
    fun overlaps(other: ClientTunCidr) = bits == other.bits && number <= other.end && other.number <= end
    fun subtract(other: ClientTunCidr): List<ClientTunCidr> {
        if (!overlaps(other)) return listOf(this)
        if (other.number <= number && other.end >= end) return emptyList()
        val next = prefix + 1
        return listOf(ClientTunCidr(number, next, bits), ClientTunCidr(number.add(BigInteger.ONE.shiftLeft(bits - next)), next, bits))
            .flatMap { it.subtract(other) }
    }
    override fun toString(): String {
        val raw = number.toByteArray(); val bytes = ByteArray(bits / 8)
        val count = minOf(raw.size, bytes.size); raw.copyInto(bytes, bytes.size - count, raw.size - count)
        return "${InetAddress.getByAddress(bytes).hostAddress}/$prefix"
    }
    companion object {
        fun parse(text: String, network: Boolean = true): ClientTunCidr {
            val parts = text.trim().split('/'); require(parts.size == 2) { "Нужен IP/префикс: $text" }
            val host = parts[0]
            val ipv6 = ':' in host
            require(if (ipv6) host.matches(Regex("[0-9a-fA-F:]+")) else host.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Только числовой IPv4/IPv6: $text" }
            if (!ipv6) require(host.split('.').all { it.toIntOrNull() in 0..255 }) { "Некорректный IPv4: $text" }
            val bytes = InetAddress.getByName(host).address
            require(bytes.size == if (ipv6) 16 else 4) { "IPv4-mapped IPv6 не поддерживается." }
            val bits = bytes.size * 8; val prefix = parts[1].toIntOrNull()
            require(prefix != null && prefix in 0..bits) { "Некорректный префикс: $text" }
            val value = BigInteger(1, bytes)
            val masked = value.shiftRight(bits - prefix).shiftLeft(bits - prefix)
            return ClientTunCidr(if (network) masked else value, prefix, bits)
        }
        fun lines(text: String): List<ClientTunCidr> {
            require(text.length <= 65536) { "Список сетей слишком большой." }
            val lines = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
            require(lines.size <= 512) { "Допускается до 512 сетей." }
            return lines.map { parse(it) }.distinct()
        }
        fun excluding(ranges: List<ClientTunCidr>, protected: List<ClientTunCidr>): List<ClientTunCidr> {
            var result = ranges.distinct()
            protected.forEach { keep -> result = result.flatMap { it.subtract(keep) }; require(result.size <= 8192) { "Слишком сложные исключения TUN." } }
            return result.distinct()
        }
    }
}
