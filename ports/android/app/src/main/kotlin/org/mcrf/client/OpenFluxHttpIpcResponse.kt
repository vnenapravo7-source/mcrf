package org.mcrf.client

import java.security.MessageDigest

/** Remote HTTP response assembly, no Android/UTF8/page assumption. Caller must
 * validate Parcel metadata and bind this to one exact queued request first. */
internal class OpenFluxHttpIpcResponse(
    val request: String, val status: Int, headers: List<Pair<String, String>>, private val total: Int,
    private val sha256: String, private val current: () -> Boolean
) : AutoCloseable {
    class Response(val request: String, val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) : AutoCloseable {
        override fun close() { body.fill(0) }
        override fun toString() = "OpenFluxHttpClientResponse(redacted)"
    }
    private val headers = headers.toList()
    private var bytes: ByteArray?
    private var offset = 0
    private var complete = false
    init {
        require(request.matches(Regex("[0-9a-f]{32}")) && status in 200..599 &&
            total in 0..OpenFluxHttpIpc.MAX_RESPONSE && sha256.matches(Regex("[0-9a-f]{64}")) &&
            (status !in setOf(204, 304) || total == 0) && OpenFluxHttpIpc.encodeHeaders(this.headers) != null)
        bytes = ByteArray(total)
    }
    override fun toString() = "OpenFluxHttpIpcResponse(redacted)"
    private fun live() = try { current() } catch (_: Exception) { false }
    @Synchronized fun nextOffset(): Int = offset
    @Synchronized fun append(expectedRequest: String, expectedOffset: Int, chunk: ByteArray, last: Boolean): Boolean {
        if (expectedRequest != request) return false
        val target = bytes ?: return false
        if (!live() || complete || expectedOffset != offset || offset % OpenFluxHttpIpc.CHUNK != 0 ||
            chunk.size != minOf(OpenFluxHttpIpc.CHUNK, total - offset) || last != (offset + chunk.size == total)) {
            close(); return false
        }
        chunk.copyInto(target, offset); offset += chunk.size
        if (last) {
            val digest = MessageDigest.getInstance("SHA-256").digest(target).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (digest != sha256) { close(); return false }
            complete = true
        }
        return true // Empty body still requires one exact empty terminal pull.
    }
    @Synchronized fun take(expectedRequest: String): Response? {
        if (expectedRequest != request) return null
        val target = bytes ?: return null
        if (!live()) { close(); return null }
        if (!complete) return null
        bytes = null; complete = false
        return Response(request, status, headers, target)
    }
    @Synchronized override fun close() { bytes?.fill(0); bytes = null; complete = false; offset = 0 }
}
