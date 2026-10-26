package org.mcrf.client

import java.security.MessageDigest

/** Single scoped upload foundation. Future endpoint permits only ONE instance;
 * incomplete uploads are not queued/native requests. No IO under this monitor. */
internal class OpenFluxHttpUpload(val id: String, total: Int, private val current: () -> Boolean) : AutoCloseable {
    private var bytes: ByteArray?
    private var received = 0
    init { require(id.matches(ID) && total in 0..OpenFluxHttpIpc.MAX_UPLOAD); bytes = ByteArray(total) }
    override fun toString() = "OpenFluxHttpUpload(redacted)"
    private fun live() = try { current() } catch (_: Exception) { false }
    @Synchronized fun append(expectedId: String, offset: Int, chunk: ByteArray): Boolean {
        if (expectedId != id) return false
        val target = bytes ?: return false
        if (!live()) { close(); return false }
        val expected = minOf(OpenFluxHttpIpc.CHUNK, target.size - received)
        if (expected <= 0 || offset != received || offset % OpenFluxHttpIpc.CHUNK != 0 || chunk.size != expected) {
            close(); return false
        }
        chunk.copyInto(target, received); received += chunk.size; return true
    }
    /** Transfers owned complete bytes ONCE; caller enqueues a defensive copy in
     * the protocol session then wipes this array. Not native admission proof. */
    @Synchronized fun take(expectedId: String): ByteArray? {
        if (expectedId != id) return null
        val target = bytes ?: return null
        if (!live()) { close(); return null }
        if (received != target.size) return null
        bytes = null; received = 0; return target
    }
    @Synchronized override fun close() { bytes?.fill(0); bytes = null; received = 0 }
    companion object { private val ID = Regex("[0-9a-f]{32}") }
}

/** Adopts a verified response from the protocol session; previous owner must
 * stop using its mutable body. Metadata is separate from chunk Parcel replies.
 * Admitted chunk copies/immutable header strings cannot be revoked or wiped here. */
internal class OpenFluxHttpResponseDelivery private constructor(
    private var response: OpenFluxHttpSession.Response?, headersJson: String, private val current: () -> Boolean
) : AutoCloseable {
    class Metadata(val request: String, val status: Int, val headersJson: String,
                   val total: Int, val sha256: String) {
        override fun toString() = "OpenFluxHttpResponseMetadata(redacted)"
    }
    class Chunk(val request: String, val offset: Int, val bytes: ByteArray, val last: Boolean) : AutoCloseable {
        override fun close() { bytes.fill(0) }
        override fun toString() = "OpenFluxHttpResponseChunk(redacted)"
    }
    private var meta: Metadata? = requireNotNull(response).let { value ->
        Metadata(value.request, value.status, headersJson, value.body.size,
            MessageDigest.getInstance("SHA-256").digest(value.body).joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    private var offset = 0
    private var metadataIssued = false
    private fun live() = try { current() } catch (_: Exception) { false }
    override fun toString() = "OpenFluxHttpResponseDelivery(redacted)"
    @Synchronized fun metadata(expectedId: String): Metadata? {
        val info = meta ?: return null
        if (expectedId != info.request || response == null) return null
        if (!live()) { close(); return null }
        metadataIssued = true; return info // Issued locally, not a remote receipt/render proof.
    }
    @Synchronized fun read(expectedId: String, expectedOffset: Int): Chunk? {
        val info = meta ?: return null
        if (expectedId != info.request) return null
        val source = response ?: return null
        if (!live() || !metadataIssued || expectedOffset != offset || expectedOffset < 0 || expectedOffset % 8192 != 0) {
            close(); return null
        }
        val end = minOf(offset + OpenFluxHttpIpc.CHUNK, source.body.size)
        val bytes = source.body.copyOfRange(offset, end)
        val result = Chunk(info.request, offset, bytes, end == source.body.size)
        offset = end
        if (result.last) close()
        return result // Empty response admits exactly one empty terminal chunk.
    }
    @Synchronized override fun close() { response?.close(); response = null; meta = null; offset = 0 }
    companion object {
        /** Failure also retires the attempted response ownership, including bytes. */
        fun adopt(value: OpenFluxHttpSession.Response, current: () -> Boolean): OpenFluxHttpResponseDelivery? {
            return try {
                require(value.request.matches(Regex("[0-9a-f]{32}")) && value.status in 200..599 &&
                    value.body.size <= OpenFluxHttpIpc.MAX_RESPONSE && (value.status !in setOf(204, 304) || value.body.isEmpty()) &&
                    OpenFluxHttpWire.headersAllowed(value.headers.map { it.name to it.value }))
                val encoded = OpenFluxHttpIpc.encodeHeaders(value.headers.map { it.name to it.value })
                    ?: throw IllegalArgumentException("header metadata bound")
                OpenFluxHttpResponseDelivery(value, encoded, current)
            } catch (_: Exception) { value.close(); null }
        }
    }
}
