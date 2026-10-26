package org.mcrf.client

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Counter observations are not health proofs. One final record, bound to one child. */
internal class OpenFluxObservation {
    companion object {
        const val PREFIX = "MCRF_OPENFLUX_OBSERVATION_V1 "
        internal val KEYS = setOf(
            "session_observed", "session_started", "session_ready", "session_stopped_before_cleanup",
            "carrier_present", "carrier_started", "carrier_dead", "hello_sent", "hello_received", "hello_accepted", "hello_rejected",
            "data_sent", "data_received", "data_dropped", "envelope_decode_failed", "unknown_kind",
            "classic_sent", "classic_received", "classic_dropped", "session_crypto_received", "session_crypto_failed", "session_crypto_replay", "session_crypto_bad_header_or_length",
            "classic_crypto_received", "classic_crypto_failed", "classic_crypto_replay", "classic_crypto_bad_header_or_length",
            "codec_unknown", "codec_send_failed", "batch_send_failed", "batch_queue_waited", "proxy_running_before_stop", "proxy_ready_seen", "stop_returned"
        )
        // Deliberately narrower than general JSON: exact producer's ASCII names/booleans.
        // Duplicate keys, quoted booleans, extra fields and JSON coercions never pass.
        private val field = Regex("\"([a-z_]+)\"[ \\t\\r\\n]*:[ \\t\\r\\n]*(true|false)")
        private val whitespace = charArrayOf(' ', '\t', '\r', '\n')
        private val problems = mapOf(
            "carrier_dead" to "носитель отмечался недоступным",
            "hello_rejected" to "отказ рукопожатия",
            "data_dropped" to "отброшенные данные",
            "classic_dropped" to "отброшенные classic-данные",
            "envelope_decode_failed" to "ошибка декодирования",
            "unknown_kind" to "неизвестный тип пакета",
            "session_crypto_failed" to "ошибка расшифровки",
            "session_crypto_replay" to "повтор session-пакета",
            "session_crypto_bad_header_or_length" to "неверный session-заголовок",
            "classic_crypto_failed" to "ошибка classic-расшифровки",
            "classic_crypto_replay" to "повтор classic-пакета",
            "classic_crypto_bad_header_or_length" to "неверный classic-заголовок",
            "codec_unknown" to "неизвестный codec-пакет",
            "codec_send_failed" to "ошибка codec-отправки",
            "batch_send_failed" to "ошибка batch-отправки"
        )
    }
    private var flags: Map<String, Boolean>? = null
    private var invalid = false
    private var finished = false

    /** True means private record consumed, including rejected records: never log raw bytes. */
    @Synchronized fun offer(bytes: ByteArray): Boolean {
        if (!bytes.take(PREFIX.length).toByteArray().toString(Charsets.US_ASCII).startsWith(PREFIX)) return false
        if (finished) return true
        if (invalid || flags != null || bytes.size > 2100) { invalid = true; return true }
        val parsed = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removeSuffix("\r")
            val body = text.removePrefix(PREFIX).trim(*whitespace)
            require(body.startsWith("{") && body.endsWith("}"))
            val result = LinkedHashMap<String, Boolean>()
            body.substring(1, body.length - 1).split(',').forEach {
                val match = requireNotNull(field.matchEntire(it.trim(*whitespace)))
                val key = match.groupValues[1]
                require(key in KEYS && key !in result)
                result[key] = match.groupValues[2] == "true"
            }
            require(result.keys == KEYS)
            result
        } catch (_: Exception) { null }
        if (parsed == null) invalid = true else flags = parsed
        return true
    }

    /** Process death and EOF are independent prerequisites; STOP text alone is not proof. */
    @Synchronized fun finish(normalExit: Boolean, drained: Boolean): String? {
        if (finished) return null
        finished = true
        val seen = flags
        flags = null
        if (invalid || !normalExit || !drained || seen == null || seen["stop_returned"] != true) return null
        val failures = problems.filterKeys { seen[it] == true }.values
        val session = if (seen["session_observed"] == true) "счётчики сеанса получены" else "сеанс не наблюдался"
        val readiness = if (seen["proxy_ready_seen"] == true) "готовность отмечена" else "готовность не наблюдалась"
        val detail = if (failures.isEmpty()) "указанные счётчики ошибок не сработали; это не проверка связи" else "наблюдались: " + failures.joinToString(", ")
        return "OpenFlux · завершение процесса и чтение подтверждены; $session; $readiness; $detail."
    }
}
