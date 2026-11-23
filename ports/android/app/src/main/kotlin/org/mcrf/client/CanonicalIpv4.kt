package org.mcrf.client

import java.net.InetAddress

/** Fast path ONLY for canonical dotted-decimal IPv4. Null means use the owner's existing fallback.
 * ASCII, exactly four octets, no leading zeros. No resolver, hostname, reverse lookup or cache.
 */
internal object CanonicalIpv4 {
    fun parse(value: String): InetAddress? {
        // Reject domain/IPv6/non-ASCII starts before any byte-array allocation.
        if (value.isEmpty() || value[0] !in '0'..'9') return null
        if (value.length !in 7..15) return null
        var index = 0
        var packed = 0
        repeat(4) { part ->
            val start = index
            var octet = 0
            while (index < value.length && value[index] in '0'..'9') {
                if (index - start == 3) return null
                octet = octet * 10 + (value[index] - '0')
                index++
            }
            if (index == start || octet > 255 || (index - start > 1 && value[start] == '0')) return null
            packed = (packed shl 8) or octet
            if (part < 3) {
                if (index == value.length || value[index] != '.') return null
                index++
            } else if (index != value.length) return null
        }
        return InetAddress.getByAddress(byteArrayOf(
            (packed ushr 24).toByte(), (packed ushr 16).toByte(), (packed ushr 8).toByte(), packed.toByte()
        ))
    }
}
