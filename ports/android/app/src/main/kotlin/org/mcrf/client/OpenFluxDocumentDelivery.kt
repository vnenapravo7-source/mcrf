package org.mcrf.client

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/** One sequential bounded pull after full native document verification. Not a
 * receipt that a Binder peer received/rendered the last admitted byte copy. */
internal class OpenFluxDocumentDelivery(html: String) : AutoCloseable {
    private var source: ByteArray? = null
    private var offset = 0
    val total: Int
    val sha256: String
    class Chunk(val total: Int, val sha256: String, val offset: Int, val bytes: ByteArray) : AutoCloseable {
        override fun close() { bytes.fill(0) }
        override fun toString() = "OpenFluxDocumentDeliveryChunk(redacted)"
    }
    init {
        require(html.length in 1..OpenFluxOwnDocument.MAX_BYTES)
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(html))
        try {
            require(encoded.remaining() in 1..OpenFluxOwnDocument.MAX_BYTES)
            source = ByteArray(encoded.remaining()).also { encoded.get(it) }
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
            else { encoded.clear(); while (encoded.hasRemaining()) encoded.put(0) }
        }
        total = requireNotNull(source).size
        sha256 = MessageDigest.getInstance("SHA-256").digest(requireNotNull(source))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun toString() = "OpenFluxDocumentDelivery(redacted)"
    @Synchronized fun read(expectedOffset: Int): Chunk? {
        val bytes = source ?: return null
        if (expectedOffset != offset || offset >= total) { close(); return null }
        val end = minOf(offset + OpenFluxOwnDocument.CHUNK_BYTES, total)
        val chunk = Chunk(total, sha256, offset, bytes.copyOfRange(offset, end))
        offset = end
        if (end == total) close()
        return chunk
    }
    @Synchronized override fun close() { source?.fill(0); source = null }
}

/** Separate from the ASCII cookie protocol. Caller owns/wipes returned commands. */
internal object OpenFluxOwnCommand {
    private val id = Regex("[0-9a-f]{32}")
    fun read(ticket: String): ByteArray? = if (ticket.matches(id))
        "OPENFLUX_DOCUMENT_READ|$ticket\n".toByteArray(Charsets.US_ASCII) else null
    fun reply(ticket: String, payload: String): ByteArray? {
        if (!ticket.matches(id) || !OpenFluxOwnPageSession.admissible(payload)) return null
        val raw = payload.toByteArray(Charsets.UTF_8)
        return try {
            ("OPENFLUX_DOCUMENT_REPLY|$ticket|" + Base64.getEncoder().encodeToString(raw) + "\n")
                .toByteArray(Charsets.US_ASCII)
        } finally { raw.fill(0) }
    }
}
