package org.mcrf.client

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.net.URI
import org.json.JSONArray
import org.json.JSONTokener

/** Distinct private descriptor. Service source exists; production HTTP remains off.
 * Header metadata and body chunks MUST use separate replies to stay below
 * MAX_PARCEL even after UTF16 strings, length fields and padding. */
internal object OpenFluxHttpIpc {
    const val DESCRIPTOR = "org.mcrf.client.OpenFluxHttpVerification.v1"
    const val MAX_PARCEL = 32768
    const val CHUNK = 8192
    const val MAX_UPLOAD = 65536
    const val MAX_RESPONSE = 524288
    const val MAX_HEADER_JSON = 12288 // One UTF16 Parcel string <=24KiB, metadata fields separate from body.
    const val ATTACH = 1
    const val POLL = 2
    const val FOREGROUND = 3
    const val OPEN = 4
    const val BEGIN = 5
    const val PUSH = 6
    const val COMMIT = 7
    const val METADATA = 8
    const val PULL = 9
    const val RELEASE = 10
    const val REPLY = 11
    const val REPLY_STATUS = 12
    fun deadline(previous: Long?, requestStarted: Long, remainingMillis: Long): Long? {
        if (remainingMillis !in 1..150000) return null
        val candidate = try { Math.addExact(requestStarted, Math.multiplyExact(remainingMillis, 1_000_000L)) }
            catch (_: ArithmeticException) { return null }
        return if (previous == null) candidate else minOf(previous, candidate)
    }
    fun address(value: String): URI? {
        if (value.isEmpty() || utf8Size(value, 8192) == null ||
            value.any { it.code <= 32 || it.code == 127 || it == '\\' }) return null
        val uri = try { URI(value) } catch (_: Exception) { return null }
        return uri.takeIf { it.scheme == "http" && it.host == "127.0.0.1" && it.port in 1..65535 &&
            it.rawAuthority == "127.0.0.1:${it.port}" && it.rawUserInfo == null && it.rawFragment == null }
    }
    fun utf8Size(value: String, limit: Int): Int? {
        if (value.length > limit) return null
        val encoded = try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)) } catch (_: Exception) { return null }
        return try { encoded.remaining().takeIf { it <= limit } } finally {
            if (encoded.hasArray()) encoded.array().fill(0) else { encoded.clear(); while (encoded.hasRemaining()) encoded.put(0) }
        }
    }
    fun headers(text: String): List<Pair<String, String>>? {
        if (utf8Size(text, MAX_HEADER_JSON) == null || !OpenFluxJsonNesting.bounded(text)) return null
        val array = try {
            val reader = JSONTokener(text); val value = reader.nextValue()
            if (reader.nextClean() != '\u0000') return null
            value as? JSONArray ?: return null
        } catch (_: Exception) { return null }
        if (array.length() > 512) return null
        val pairs = ArrayList<Pair<String, String>>()
        for (i in 0 until array.length()) {
            val pair = array.opt(i) as? JSONArray ?: return null
            if (pair.length() != 2) return null
            pairs.add((pair.opt(0) as? String ?: return null) to (pair.opt(1) as? String ?: return null))
        }
        return pairs.takeIf(OpenFluxHttpWire::headersAllowed)?.toList()
    }
    fun encodeHeaders(pairs: List<Pair<String, String>>): String? {
        if (!OpenFluxHttpWire.headersAllowed(pairs)) return null
        var flattened = 0
        for ((name, value) in pairs) {
            flattened += (utf8Size(name, 8192) ?: return null) + (utf8Size(value, 8192) ?: return null) + 8
            if (flattened > MAX_HEADER_JSON) return null
        }
        val array = JSONArray(); pairs.forEach { array.put(JSONArray().put(it.first).put(it.second)) }
        val text = array.toString()
        return text.takeIf { utf8Size(it, MAX_HEADER_JSON) != null }
    }
}
