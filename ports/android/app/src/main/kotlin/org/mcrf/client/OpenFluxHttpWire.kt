package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.net.URI
import java.util.Base64
import java.util.Locale

/** Reserved private HTTP family. No production dispatcher/HTTP capability yet.
 * Recognize before UTF8 decoding and ALL diagnostic sinks, even malformed data. */
internal object OpenFluxHttpWire {
    const val PENDING = "MCRF_OPENFLUX_HTTP_PENDING "
    const val META = "MCRF_OPENFLUX_HTTP_RESPONSE "
    const val CHUNK = "MCRF_OPENFLUX_HTTP_CHUNK "
    const val END = "MCRF_OPENFLUX_HTTP_END "
    const val OPENED = "MCRF_OPENFLUX_HTTP_OPENED "
    const val RESULT = "MCRF_OPENFLUX_HTTP_RESULT "
    private val family = "MCRF_OPENFLUX_HTTP".toByteArray(Charsets.US_ASCII)
    private val idPattern = Regex("[0-9a-f]{32}")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private const val EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    class Pending(val id: String) { override fun toString() = "OpenFluxHttpPending(redacted)" }
    class Header(val name: String, val value: String) { override fun toString() = "OpenFluxHttpHeader(redacted)" }
    class Metadata(val id: String, val request: String, val status: Int, val headers: List<Header>, val bytes: Int, val sha256: String) {
        override fun toString() = "OpenFluxHttpMetadata(redacted)"
    }
    class Chunk(val id: String, val request: String, val offset: Int, val bytes: ByteArray) : AutoCloseable {
        override fun close() { bytes.fill(0) }
        override fun toString() = "OpenFluxHttpChunk(redacted)"
    }
    class End(val id: String, val request: String) { override fun toString() = "OpenFluxHttpEnd(redacted)" }
    class Opened(val id: String, val request: String, val permit: String, val address: String) {
        override fun toString() = "OpenFluxHttpOpened(redacted)"
    }
    class Result(val id: String, val request: String, val code: String) { override fun toString() = "OpenFluxHttpResult(redacted)" }
    fun recognized(bytes: ByteArray) = bytes.size >= family.size && family.indices.all { bytes[it] == family[it] }
    fun discardDisabled(bytes: ByteArray, closed: Boolean, reject: () -> Unit): Boolean {
        if (!recognized(bytes)) return false
        if (!closed) reject()
        return true
    }
    private fun record(bytes: ByteArray, prefix: String, max: Int): JSONObject? = try {
        if (bytes.size !in 1..max) null else {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removeSuffix("\r")
            if (!text.startsWith(prefix) || !OpenFluxJsonNesting.bounded(text.removePrefix(prefix))) null else {
                val reader = JSONTokener(text.removePrefix(prefix)); val value = reader.nextValue()
                if (reader.nextClean() != '\u0000') null else value as? JSONObject
            }
        }
    } catch (_: Exception) { null }
    private fun keys(data: JSONObject) = data.keys().asSequence().toSet()
    private fun id(data: JSONObject, key: String) = (data.opt(key) as? String)?.takeIf { it.matches(idPattern) }
    private fun integer(data: JSONObject, key: String, range: IntRange): Int? {
        val number = when (val value = data.opt(key)) { is Int -> value.toLong(); is Long -> value; else -> return null }
        return number.takeIf { it in range.first.toLong()..range.last.toLong() }?.toInt()
    }
    private fun utf8Size(value: String): Int? {
        if (value.length > 8192) return null
        val encoded = try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)) } catch (_: Exception) { return null }
        return try { encoded.remaining() } finally {
            if (encoded.hasArray()) encoded.array().fill(0) else { encoded.clear(); while (encoded.hasRemaining()) encoded.put(0) }
        }
    }
    private fun headers(data: JSONArray): List<Header>? {
        if (data.length() > 512) return null
        val counts = mutableMapOf<String, Int>(); var size = 0
        val result = ArrayList<Header>()
        for (index in 0 until data.length()) {
            val pair = data.opt(index) as? JSONArray ?: return null
            if (pair.length() != 2) return null
            val name = pair.opt(0) as? String ?: return null; val value = pair.opt(1) as? String ?: return null
            if (name.isEmpty() || name.length > 8192 || name.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~") }) return null
            if (value.any { it.code < 0x20 && it != '\t' || it.code == 0x7f }) return null
            val key = name.lowercase(Locale.ROOT)
            if (key in setOf("connection", "proxy-authorization", "proxy-authenticate", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length")) return null
            val count = (counts[key] ?: 0) + 1; if (count > 16) return null
            if (key !in counts) size += name.length + 2
            counts[key] = count; if (counts.size > 32) return null
            size += (utf8Size(value) ?: return null) + 2; if (size > 8192) return null
            result.add(Header(name, value))
        }
        return result.toList()
    }
    fun headersAllowed(pairs: List<Pair<String, String>>): Boolean {
        if (pairs.size > 512 || pairs.any { it.first.length > 8192 || it.second.length > 8192 }) return false
        val array = JSONArray()
        pairs.forEach { array.put(JSONArray().put(it.first).put(it.second)) }
        return headers(array) != null
    }
    fun pending(bytes: ByteArray): Pending? {
        val data = record(bytes, PENDING, 256) ?: return null
        if (keys(data) != setOf("id", "kind") || data.opt("kind") != "own_http") return null
        return Pending(id(data, "id") ?: return null)
    }
    fun metadata(bytes: ByteArray): Metadata? {
        val data = record(bytes, META, 24576) ?: return null
        if (keys(data) != setOf("id", "request", "status", "headers", "bytes", "sha256")) return null
        val size = integer(data, "bytes", 0..524288) ?: return null
        val hash = (data.opt("sha256") as? String)?.takeIf { it.matches(hashPattern) } ?: return null
        if (size == 0 && hash != EMPTY_SHA) return null
        val status = integer(data, "status", 200..599) ?: return null
        if (status in setOf(204, 304) && size != 0) return null
        return Metadata(id(data, "id") ?: return null, id(data, "request") ?: return null, status,
            headers(data.opt("headers") as? JSONArray ?: return null) ?: return null, size, hash)
    }
    fun chunk(bytes: ByteArray): Chunk? {
        val data = record(bytes, CHUNK, 12000) ?: return null
        if (keys(data) != setOf("id", "request", "offset", "data")) return null
        val nativeId = id(data, "id") ?: return null; val request = id(data, "request") ?: return null
        val offset = integer(data, "offset", 0 until 524288) ?: return null; if (offset % 8192 != 0) return null
        val encoded = (data.opt("data") as? String)?.takeIf { it.length in 4..10924 && it.length % 4 == 0 } ?: return null
        val raw = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
        if (raw.size !in 1..8192 || Base64.getEncoder().encodeToString(raw) != encoded) { raw.fill(0); return null }
        return Chunk(nativeId, request, offset, raw)
    }
    fun end(bytes: ByteArray): End? {
        val data = record(bytes, END, 256) ?: return null
        if (keys(data) != setOf("id", "request")) return null
        return End(id(data, "id") ?: return null, id(data, "request") ?: return null)
    }
    fun opened(bytes: ByteArray): Opened? {
        val data = record(bytes, OPENED, 24576) ?: return null
        if (keys(data) != setOf("id", "request", "permit", "address")) return null
        val address = data.opt("address") as? String ?: return null
        if (address.isEmpty() || (utf8Size(address) ?: return null) > 8192 ||
            address.any { it.code <= 32 || it.code == 127 || it == '\\' }) return null
        val uri = try { URI(address) } catch (_: Exception) { return null }
        if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port !in 1..65535 ||
            uri.rawAuthority != "127.0.0.1:${uri.port}" || uri.rawUserInfo != null || uri.rawFragment != null) return null
        return Opened(id(data, "id") ?: return null, id(data, "request") ?: return null, id(data, "permit") ?: return null, address)
    }
    fun result(bytes: ByteArray): Result? {
        val data = record(bytes, RESULT, 256) ?: return null
        if (keys(data) != setOf("id", "request", "code")) return null
        val code = data.opt("code") as? String ?: return null
        if (code !in setOf("open_failed", "request_failed", "response_rejected", "reply_failed", "accepted")) return null
        return Result(id(data, "id") ?: return null, id(data, "request") ?: return null, code)
    }
}
