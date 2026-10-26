package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class OpenFluxObservationReviewTest {
    private fun record(vararg yes: String): ByteArray = (OpenFluxObservation.PREFIX +
        OpenFluxObservation.KEYS.joinToString(",", "{", "}") { "\"$it\":" + (it in yes) }).toByteArray()
    private fun accepted(bytes: ByteArray): String? = OpenFluxObservation().run { offer(bytes); finish(true, true) }

    @Test fun completeFiniteSchemaDoesNotClaimNetworkHealthy() {
        assertEquals(34, OpenFluxObservation.KEYS.size)
        val summary = accepted(record("stop_returned", "session_observed", "proxy_ready_seen"))!!
        assertTrue(summary.contains("это не проверка связи"))
        assertTrue(summary.contains("готовность отмечена"))
    }
    @Test fun missingSessionAndQueueWaitAreNotInventedFailures() {
        val summary = accepted(record("stop_returned", "batch_queue_waited"))!!
        assertTrue(summary.contains("сеанс не наблюдался"))
        assertFalse(summary.contains("наблюдались:"))
    }
    @Test fun errorsAreFiniteAndDoNotExposeUnknownProviderFields() {
        assertTrue(accepted(record("stop_returned", "session_crypto_failed"))!!.contains("ошибка расшифровки"))
        assertNull(accepted(record("stop_returned").toString(Charsets.UTF_8).replace("}", ",\"url\":\"https://synthetic.private/\"}").toByteArray()))
    }
    @Test fun duplicatesCoercionsMissingKeysAndTrailingDataAreRejected() {
        val text = record("stop_returned").toString(Charsets.UTF_8)
        for (invalid in listOf(text.replace("}", ",\"stop_returned\":true}"), text.replace(":true", ":\"true\""),
            text.replace("\"session_observed\":false,", ""), text + " trailing", text.replace(":false", ":0")))
            assertNull(accepted(invalid.toByteArray()))
    }
    @Test fun malformedUtf8OversizeAndSecondRecordPoisonThisChildOnly() {
        assertNull(accepted(record("stop_returned") + byteArrayOf(0xc3.toByte(), 0x28)))
        assertNull(accepted((OpenFluxObservation.PREFIX + " ".repeat(2200)).toByteArray()))
        val reader = OpenFluxObservation()
        reader.offer(record("stop_returned")); reader.offer(record("stop_returned"))
        assertNull(reader.finish(true, true))
        assertNotNull(accepted(record("stop_returned")))
    }
    @Test fun missingRecordIncompleteDrainKilledChildAndMissingStopNeverPass() {
        assertNull(OpenFluxObservation().finish(true, true))
        for ((normal, drain) in listOf(false to true, true to false, false to false)) {
            val reader = OpenFluxObservation(); reader.offer(record("stop_returned"))
            assertNull(reader.finish(normal, drain))
        }
        assertNull(accepted(record()))
    }
    @Test fun recordIsConsumedOnceAndUnrelatedLogsAreNotRetained() {
        val reader = OpenFluxObservation()
        assertFalse(reader.offer("https://synthetic.private/".toByteArray()))
        assertTrue(reader.offer(record("stop_returned")))
        assertNotNull(reader.finish(true, true))
        assertTrue(reader.offer(record("stop_returned")))
        assertNull(reader.finish(true, true))
    }
}
