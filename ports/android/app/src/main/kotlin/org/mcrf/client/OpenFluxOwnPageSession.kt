package org.mcrf.client

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject
import org.json.JSONTokener

/** One ephemeral structured reply. Staging is not permission to send it. */
internal class OpenFluxOwnPageSession(private val current: () -> Boolean) : AutoCloseable {
    private var pending: String? = null
    private var closed = false
    private var submitted = false
    class Submission internal constructor(val payload: String) {
        override fun toString() = "OpenFluxOwnSubmission(redacted)"
    }
    private var inFlight: Submission? = null
    override fun toString() = "OpenFluxOwnPageSession(redacted)"
    private fun live() = !closed && !submitted && try { current() } catch (_: Exception) { false }

    @Synchronized fun stage(payload: String): Boolean {
        if (!live()) { close(); return false }
        if (pending != null || !admissible(payload)) return false
        pending = payload; return true
    }
    /** Only the native app's explicit Send action may call this, never page JS. */
    @Synchronized fun beginSubmissionAfterUserAction(): Submission? {
        if (!live()) { close(); return null }
        if (inFlight != null) return null
        val value = pending ?: return null
        return Submission(value).also { inFlight = it }
    }
    @Synchronized fun submissionCurrent(attempt: Submission) = inFlight === attempt && live()
    /** Null means stale/duplicate completion, not a retryable rejection. */
    @Synchronized fun finishSubmission(attempt: Submission, accepted: Boolean): Boolean? {
        if (inFlight !== attempt) return null
        inFlight = null
        if (!live()) { close(); return null }
        if (accepted) { submitted = true; pending = null }
        return accepted
    }
    @Synchronized fun submitAfterUserAction(offer: (String) -> Boolean): Boolean {
        val attempt = beginSubmissionAfterUserAction() ?: return false
        val accepted = try { offer(attempt.payload) } catch (_: Exception) { false }
        return finishSubmission(attempt, accepted) == true // Queue acceptance is not a native ACK/connection receipt.
    }
    @Synchronized override fun close() { closed = true; pending = null; inFlight = null }

    companion object {
        const val MAX_BYTES = 8192
        /** Bounded shape gate; native strict JSON/FlattenSubmission is authoritative.
         * Android JSONTokener is lenient, so this is not advertised as a strict parser. */
        fun admissible(payload: String): Boolean {
            if (payload.length !in 2..MAX_BYTES || !OpenFluxJsonNesting.bounded(payload)) return false
            val bytes = try {
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(payload))
            } catch (_: Exception) { return false }
            try { if (bytes.remaining() > MAX_BYTES) return false }
            finally { if (bytes.hasArray()) bytes.array().fill(0) else { bytes.clear(); while (bytes.hasRemaining()) bytes.put(0) } }
            return try {
                val reader = JSONTokener(payload)
                val root = reader.nextValue() as? JSONObject ?: return false
                if (reader.nextClean() != '\u0000') return false
                val values = root.opt("client") as? JSONObject ?: root
                values.keys().asSequence().any { key ->
                    val value = values.opt(key)
                    key.isNotEmpty() && (value is String || value is Boolean || value is Number)
                }
            } catch (_: Exception) { false }
        }
    }
}
