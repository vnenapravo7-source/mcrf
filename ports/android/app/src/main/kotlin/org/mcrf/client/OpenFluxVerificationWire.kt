package org.mcrf.client

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Private child-pipe records. Never pass these records to diagnostics or persistence. */
internal object OpenFluxVerificationWire {
    const val PENDING = "MCRF_OPENFLUX_VERIFY "
    const val REPLY = "MCRF_OPENFLUX_REPLY "
    class Pending(val nativeId: String, val page: OpenFluxVerificationPolicy.Page) {
        override fun toString() = "OpenFluxVerificationPending(redacted)"
    }
    class Reply(val nativeId: String, val status: String) {
        override fun toString() = "OpenFluxVerificationReplyResult(redacted)"
    }
    fun recognized(line: String) = line.startsWith(PENDING) || line.startsWith(REPLY)
    private fun record(bytes: ByteArray, prefix: String): JSONObject? = try {
        if (bytes.size !in 1..16416) null else {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removeSuffix("\r")
            if (!text.startsWith(prefix) || !OpenFluxJsonNesting.bounded(text.removePrefix(prefix))) null else {
                val reader = JSONTokener(text.removePrefix(prefix))
                val value = reader.nextValue()
                if (reader.nextClean() != '\u0000') null else value as? JSONObject
            }
        }
    } catch (_: Exception) { null }
    private fun nativeId(data: JSONObject): String? = (data.opt("id") as? String)?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
    private fun keys(data: JSONObject) = data.keys().asSequence().toSet()
    fun pending(bytes: ByteArray): Pending? {
        val data = record(bytes, PENDING) ?: return null
        if (keys(data).any { it !in setOf("id", "url", "proxy", "own") }) return null
        val id = nativeId(data) ?: return null
        if (data.opt("own") != false) return null
        val url = data.opt("url") as? String ?: return null
        val proxy = if (data.has("proxy")) data.opt("proxy") as? String ?: return null else ""
        val page = OpenFluxVerificationPolicy.externalPage(url, "", false, proxy) ?: return null
        return Pending(id, page)
    }
    fun reply(bytes: ByteArray): Reply? {
        val data = record(bytes, REPLY) ?: return null
        if (keys(data) != setOf("id", "status")) return null
        val id = nativeId(data) ?: return null
        val status = data.opt("status") as? String ?: return null
        if (status !in setOf("accepted", "stale_or_unsupported", "reply_failed")) return null
        return Reply(id, status)
    }
}
