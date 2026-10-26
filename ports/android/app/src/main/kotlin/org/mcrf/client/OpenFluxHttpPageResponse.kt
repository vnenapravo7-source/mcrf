package org.mcrf.client

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray

/** One owned verified binary response -> bounded page-port frames. No port,
 * renderer or consumer ACK here. Caller sends at most one next() per event step
 * and rechecks exact current page. Returned immutable messages not revocable. */
internal class OpenFluxHttpPageResponse private constructor(
    private val pageId: String,
    private var response: OpenFluxHttpIpcResponse.Response?,
    private val current: () -> Boolean
) : AutoCloseable {
    private var phase = 0
    private var offset = 0
    override fun toString() = "OpenFluxHttpPageResponse(redacted)"
    private fun live() = try { current() } catch (_: Exception) { false }
    @Synchronized fun next(): String? {
        val owned = response ?: return null
        if (!live()) { close(); return null }
        if (phase == 0) {
            val headers = JSONArray()
            // Never install native handler cookies into the shared WebView jar.
            // No browser-cookie bridge or cross-origin credentials are implied.
            owned.headers.filterNot { it.first.equals("set-cookie", true) || it.first.equals("set-cookie2", true) }
                .forEach { headers.put(JSONArray().put(it.first).put(it.second)) }
            val hash = MessageDigest.getInstance("SHA-256").digest(owned.body)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val message = JSONArray().put("meta").put(pageId).put(owned.status).put(headers).put(owned.body.size).put(hash).toString()
            if (OpenFluxHttpIpc.utf8Size(message, 24576) == null) { close(); return null }
            phase = 1; return message
        }
        if (offset < owned.body.size) {
            val end = minOf(offset + 8192, owned.body.size)
            val chunk = owned.body.copyOfRange(offset, end)
            return try {
                JSONArray().put("chunk").put(pageId).put(offset).put(Base64.getEncoder().encodeToString(chunk)).toString()
                    .also { offset = end }
            } finally { chunk.fill(0) }
        }
        val terminal = JSONArray().put("end").put(pageId).toString()
        close(); return terminal // Locally produced, not a remote/browser consumption receipt.
    }
    @Synchronized override fun close() { response?.close(); response = null; offset = 0 }
    companion object {
        /** Adopts on success, wipes attempted body even on validation failure. */
        fun adopt(pageId: String, expectedNativeId: String, value: OpenFluxHttpIpcResponse.Response,
                  head: Boolean, current: () -> Boolean): OpenFluxHttpPageResponse? = try {
            require(pageId.matches(ID) && expectedNativeId.matches(ID) && value.request == expectedNativeId &&
                value.status in 200..599 && value.body.size <= 524288 &&
                (!(head || value.status in setOf(204, 304)) || value.body.isEmpty()) &&
                OpenFluxHttpIpc.encodeHeaders(value.headers) != null &&
                value.headers.filter { it.first.equals("content-encoding", true) }
                    .all { it.second.trim().equals("identity", true) } && current())
            OpenFluxHttpPageResponse(pageId, value, current)
        } catch (_: Exception) { value.close(); null }
        private val ID = Regex("[0-9a-f]{32}")
    }
}
