package org.mcrf.client

import java.net.URI

/** Local config links only: opening one never fetches an arbitrary web URL. */
object ImportLink {
    val schemes = setOf("sstp","s3bypass","openflux", "csqtt", "wdtt", "qwdtt", "olcrtc", "dnstt", "vless", "vmess", "trojan", "ss", "socks", "socks5", "hysteria", "hysteria2", "hy2", "tuic", "wireguard", "wg", "awg", "anytls", "ssh", "naive+https", "happ", "mcrf", "v2raytun")
    fun config(value: String?): String? {
        if (value == null || value.toByteArray(Charsets.UTF_8).size > (if(value.startsWith("s3bypass://",true))90000 else 65536) || value.any(Char::isISOControl)) return null
        if (value.startsWith("v2raytun://", true)) return try { CompatibilityImportLinks.unwrap(value); value } catch (_: Exception) { null }
        val normalized = if (value.startsWith("qwdtt:config?", true)) "qwdtt://config?" + value.substringAfter('?') else value
        // OLC v1 has literal angle brackets in its transport options, which java.net.URI rejects.
        if (normalized.startsWith("olcrtc://", true)) return value
        val uri = try { URI(normalized) } catch (_: Exception) { return null }
        if (uri.scheme.equals("mcrf", true) && !CompatibilityImportEncrypted.isEncrypted(value)) return null
        return value.takeIf { uri.scheme?.lowercase(java.util.Locale.ROOT) in schemes }
    }
}
