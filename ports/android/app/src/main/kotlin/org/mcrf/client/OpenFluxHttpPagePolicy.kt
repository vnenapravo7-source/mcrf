package org.mcrf.client

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Pure, disabled-UI foundation: a fresh synthetic HTTPS origin maps ONLY to
 * the captured native HTTP handler origin. This does not perform network IO,
 * render HTML, approve external providers or grant an Android/WebView consent. */
internal class OpenFluxHttpPagePolicy(address: String, nonce: String) {
    private val native = requireNotNull(OpenFluxHttpIpc.address(address))
    private val nativeOrigin = "http://${native.rawAuthority}"
    val origin: String
    val initialUrl: String
    init {
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        origin = "https://$nonce.openflux.invalid"
        initialUrl = origin + path(native)
    }
    override fun toString() = "OpenFluxHttpPagePolicy(redacted)"
    private fun path(uri: URI) = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
    /** Framework interception exposes no POST body. Never pretend POST, form
     * or fetch bodies can be reconstructed from WebResourceRequest. Future JS
     * fetch bridge must use a separate bounded message/body admission path. */
    fun interceptTarget(url: String, method: String): String? {
        if (method !in setOf("GET", "HEAD")) return null
        return requestTarget(url, method)
    }
    /** Separate explicit message/body channel, not framework interception. */
    fun requestTarget(url: String, method: String): String? {
        if (method !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS") ||
            OpenFluxHttpIpc.utf8Size(url, 8192) == null ||
            url.any { it.code <= 32 || it.code == 127 || it == '\\' }) return null
        val uri = try { URI(url) } catch (_: Exception) { return null }
        val expected = URI(origin)
        if (uri.scheme != "https" || uri.rawAuthority != expected.rawAuthority || uri.host != expected.host ||
            uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null) return null
        return (nativeOrigin + path(uri)).takeIf { OpenFluxHttpIpc.address(it) != null }
    }
    /** Only the exact initially captured page is a top-level navigation. A
     * resource/redirect must not silently become another login/navigation. */
    fun initialNavigation(url: String, method: String) = method == "GET" && url == initialUrl
    /** No redirects, compressed bytes masquerading as text, charset guessing,
     * MIME sniffing or broken UTF8. Caller retains and must close its body owner.
     * Returns immutable text: not a revocable render or secure-wipe receipt. */
    fun mainHtml(status: Int, headers: List<Pair<String, String>>, bytes: ByteArray): String? {
        if (status !in 200..299 || status == 204 || bytes.size > OpenFluxHttpIpc.MAX_RESPONSE ||
            !OpenFluxHttpWire.headersAllowed(headers)) return null
        val types = headers.filter { it.first.equals("content-type", ignoreCase = true) }.map { it.second }
        val encodings = headers.filter { it.first.equals("content-encoding", ignoreCase = true) }.map { it.second }
        if (types.size != 1 || encodings.size > 1 || encodings.any { !it.trim().equals("identity", ignoreCase = true) }) return null
        val pieces = types.single().split(';').map { it.trim() }
        if (!pieces.first().equals("text/html", ignoreCase = true) || pieces.size > 2) return null
        if (pieces.size == 2 && pieces[1].lowercase(Locale.ROOT) !in setOf("charset=utf-8", "charset=\"utf-8\"")) return null
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { null }
    }
    companion object {
        // Future WebView must ALSO intercept every URL/fallback and disable
        // files/content/windows/downloads/service-workers; CSP is not that proof.
        const val CSP = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; font-src 'self' data:; connect-src 'none'; frame-src 'none'; child-src 'none'; " +
            "worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    }
}
