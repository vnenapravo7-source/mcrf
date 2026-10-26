package org.mcrf.client

import java.net.URI
import java.util.Locale

/** Separate from VK's origin/JS contract. Pure admission only, not a browser. */
internal object OpenFluxVerificationPolicy {
    class Page internal constructor(val url: String, val origin: String) {
        override fun toString() = "OpenFluxVerificationPage(redacted)"
    }

    /** Inline/script-owned and exit-side proxied pages need a different UI contract. */
    fun externalPage(url: String, html: String, own: Boolean, proxy: String): Page? {
        if (html.isNotEmpty() || own || proxy.isNotEmpty()) return null
        val origin = origin(url) ?: return null
        return Page(url, origin)
    }

    /** Exact approved origin, not suffix/subdomain matching. No implicit approval. */
    fun mayNavigate(page: Page, target: String, userApprovedOrigin: String?): Boolean =
        userApprovedOrigin == page.origin && origin(target) == page.origin

    private fun origin(raw: String): String? = try {
        if (raw.length !in 1..8192 || raw.any { it.isISOControl() || it == '\\' }) null
        else {
            val uri = URI(raw)
            val host = uri.host?.lowercase(Locale.ROOT)
            when {
                !uri.scheme.equals("https", true) || uri.userInfo != null || uri.fragment != null || uri.port !in setOf(-1, 443) -> null
                host == null || host.length > 253 || !host.contains('.') || host.endsWith('.') -> null
                host.split('.').any { !it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) } -> null
                host.substringAfterLast('.').none { it in 'a'..'z' } -> null // No IP-literal navigation.
                host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") -> null
                host in setOf("vk.com", "vk.ru") || host.endsWith(".vk.com") || host.endsWith(".vk.ru") -> null
                else -> "https://$host"
            }
        }
    } catch (_: Exception) { null }
}

/** One ephemeral reply slot bound to one child and one native request, not a global cookie sink. */
internal class OpenFluxVerificationReply(
    private val child: String,
    private val request: String,
    private val alive: () -> Boolean,
    private val offer: (ByteArray) -> Boolean,
) {
    private var closed = false
    override fun toString() = "OpenFluxVerificationReply(redacted)"

    init {
        require(child.matches(Regex("[0-9a-f]{32}")))
        require(request.matches(Regex("[0-9a-f]{32}")))
    }

    /** Rejected queue retains the slot; accepted delivery cannot be replayed. No native ACK claim. */
    @Synchronized fun submit(expectedChild: String, expectedRequest: String, cookies: String): Boolean {
        if (closed || child != expectedChild || request != expectedRequest) return false
        if (!try { alive() } catch (_: Exception) { false }) { closed = true; return false }
        if (cookies.length !in 1..8192 || cookies.any { it.code !in 0x20..0x7e }) return false
        val raw = cookies.toByteArray(Charsets.US_ASCII)
        val encoded = try { java.util.Base64.getEncoder().encodeToString(raw) } finally { raw.fill(0) }
        val command = "OPENFLUX_CAPTCHA_REPLY|$request|$encoded\n".toByteArray(Charsets.US_ASCII)
        return try {
            val accepted = try { offer(command) } catch (_: Exception) { false }
            if (accepted) closed = true
            accepted
        } finally { command.fill(0) }
    }

    @Synchronized fun close() { closed = true }
}
