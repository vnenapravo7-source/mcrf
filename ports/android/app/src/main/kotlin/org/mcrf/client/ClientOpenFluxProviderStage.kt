package org.mcrf.client

import java.util.Collections

/**
 * Collects the reviewed diagnostic CLI's single final record for one child process.
 * The caller owns the DEBUG, module/source/native identity and approved-profile gates.
 * It also owns process/reader identity: construct a fresh reader for every child.
 *
 * A staged line proves neither process death nor upstream StopProxy ordering. The
 * reviewed source and its one-run-per-process CLI supply the emitter ordering;
 * the caller must observe a normal exit (0 or 1) and drain this child's reader
 * before finishing successfully. No parent STOP timing or literal STOP ACK is
 * required here: a startup failure can clean up and exit 1 on its own.
 */
class ClientOpenFluxProviderStage private constructor(private val schema: Schema) {
    /** The public default reader remains the original fifteen-field V1 contract. */
    constructor() : this(Schema.V1)

    private enum class Schema(val prefix: String) {
        V1("MCRF_PROVIDER_STAGE_V1 "),
        V2("MCRF_PROVIDER_STAGE_V2 "),
        V3("MCRF_PROVIDER_STAGE_V3 ");

        val fields: List<String>
            get() = when (this) {
                V1 -> V1_FIELDS
                V2 -> V2_FIELDS
                V3 -> V3_FIELDS
            }
    }

    /** Only fixed field names and Boolean values can enter a returned snapshot. */
    class Snapshot internal constructor(events: Map<String, Boolean>) {
        val events: Map<String, Boolean> = Collections.unmodifiableMap(LinkedHashMap(events))
    }

    private var finished = false
    private var recordSeen = false
    private var candidate: Map<String, Boolean>? = null

    /**
     * Offers one complete stdout line. The 1024-byte bound includes every supplied
     * byte, including any line terminator. Unrecognized lines are not decoded,
     * retained or returned. A recognized malformed/oversize or second record
     * permanently rejects this lifetime; a later valid line cannot repair it.
     *
     * No events are exposed here. Offers after finish are discarded, so an
     * undrained/forced lifetime cannot be reopened by a late reader callback.
     */
    @Synchronized
    fun offer(line: ByteArray) {
        if (finished || !hasPrefix(line)) return
        if (recordSeen) {
            candidate = null
            return
        }
        recordSeen = true
        candidate = if (line.size <= MAX_RECORD_BYTES) RecordParser(line, schema).parse() else null
    }

    /**
     * Consumes this reader exactly once. Null means not_reported, never an
     * invented all-false record. Every subsequent call returns null, preventing
     * a second publication even when the first call returned a snapshot.
     *
     * gracefulExit must mean actual observed normal process death with exit 0
     * or 1, without a parent kill/forced termination. readerDrained must mean
     * this child's reader reached EOF and completed; a bounded join timing out
     * must pass false. These are caller evidence, not facts this parser proves.
     * False flags mean unobserved before the native recorder froze, not proof
     * that asynchronous work joined or that an operation never happened.
     */
    @Synchronized
    fun finish(gracefulExit: Boolean, readerDrained: Boolean): Snapshot? {
        if (finished) return null
        finished = true
        val events = candidate
        candidate = null
        return if (gracefulExit && readerDrained && events != null) Snapshot(events) else null
    }

    /**
     * A deliberately narrow flat-JSON grammar: literal known ASCII keys, JSON
     * whitespace, and true/false tokens. Escaped keys are rejected. Every
     * accepted byte is ASCII, hence valid UTF-8; malformed UTF-8 cannot be
     * replaced or coerced into a valid key/token. No native text is copied.
     */
    private class RecordParser(private val line: ByteArray, schema: Schema) {
        private val fields = schema.fields
        private var position = schema.prefix.length

        fun parse(): Map<String, Boolean>? {
            whitespace()
            if (!take('{')) return null
            val events = LinkedHashMap<String, Boolean>(fields.size)
            repeat(fields.size) { index ->
                whitespace()
                val key = key() ?: return null
                if (events.containsKey(key)) return null
                whitespace()
                if (!take(':')) return null
                whitespace()
                val value = when {
                    literal("true") -> true
                    literal("false") -> false
                    else -> return null
                }
                events[key] = value
                whitespace()
                if (!take(if (index == fields.lastIndex) '}' else ',')) return null
            }
            whitespace()
            if (position != line.size) return null
            return fields.associateWith { events.getValue(it) }
        }

        private fun key(): String? {
            if (!take('"')) return null
            val start = position
            while (position < line.size && line[position].toInt() != '"'.code) {
                val value = line[position].toInt()
                if (value !in 'a'.code..'z'.code && value != '_'.code) return null
                position++
            }
            val length = position - start
            if (!take('"')) return null
            return fields.firstOrNull { name ->
                name.length == length && name.indices.all { line[start + it].toInt() == name[it].code }
            }
        }

        private fun take(value: Char): Boolean {
            if (position >= line.size || line[position].toInt() != value.code) return false
            position++
            return true
        }

        private fun literal(value: String): Boolean {
            if (line.size - position < value.length) return false
            if (!value.indices.all { line[position + it].toInt() == value[it].code }) return false
            position += value.length
            return true
        }

        private fun whitespace() {
            while (position < line.size) {
                when (line[position].toInt()) {
                    0x20, 0x09, 0x0a, 0x0d -> position++
                    else -> return
                }
            }
        }
    }

    private fun hasPrefix(line: ByteArray): Boolean = line.size >= schema.prefix.length &&
        schema.prefix.indices.all { line[it].toInt() == schema.prefix[it].code }

    companion object {
        /** Explicit opt-in to the reviewed fixed twenty-six-field V2 contract. */
        fun v2(): ClientOpenFluxProviderStage = ClientOpenFluxProviderStage(Schema.V2)

        /** Explicit opt-in to the reviewed fixed thirty-seven-field V3 contract. */
        fun v3(): ClientOpenFluxProviderStage = ClientOpenFluxProviderStage(Schema.V3)

        private const val MAX_RECORD_BYTES = 1024
        private val V1_FIELDS = listOf(
            "mailru_socketio_ready",
            "mailru_auth_write_ok",
            "mailru_auth_write_failed",
            "mailru_auth_changes_seen",
            "mailru_ack_write_ok",
            "mailru_ack_write_failed",
            "mailru_fallback_ready",
            "mailru_chat_write_ok",
            "mailru_chat_write_failed",
            "mailru_peer_chat_decoded",
            "mailru_read_closed",
            "classic_decrypt_ok",
            "classic_decrypt_failed",
            "classic_frame_accepted",
            "classic_frame_rejected"
        )

        private val V2_FIELDS = V1_FIELDS + listOf(
            "raw_callback_missing",
            "demux_empty",
            "demux_session",
            "demux_classic",
            "layer_callback_missing",
            "codec_unknown",
            "codec_decode_failed",
            "codec_no_payload",
            "classic_header_invalid",
            "classic_direction_invalid",
            "classic_replay_rejected"
        )

        private val V3_FIELDS = V2_FIELDS + listOf(
            "sess_hdr_bad",
            "sess_dir_bad",
            "sess_dec_fail",
            "sess_dec_ok",
            "sess_batch_bad",
            "sess_env_bad",
            "sess_role_bad",
            "sess_hello_ok",
            "sess_hello_bad",
            "sess_cb_missing",
            "sess_replay"
        )
    }
}
