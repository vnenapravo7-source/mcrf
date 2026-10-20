package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ClientOpenFluxProviderStageTest {
    private val prefix = "MCRF_PROVIDER_STAGE_V1 "
    private val fields = listOf(
        "mailru_socketio_ready", "mailru_auth_write_ok", "mailru_auth_write_failed",
        "mailru_auth_changes_seen", "mailru_ack_write_ok", "mailru_ack_write_failed",
        "mailru_fallback_ready", "mailru_chat_write_ok", "mailru_chat_write_failed",
        "mailru_peer_chat_decoded", "mailru_read_closed", "classic_decrypt_ok",
        "classic_decrypt_failed", "classic_frame_accepted", "classic_frame_rejected"
    )

    private fun record(enabled: Set<String> = emptySet(), order: List<String> = fields): String =
        prefix + order.joinToString(",", "{", "}") { "\"$it\":${it in enabled}" }

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)

    private fun read(line: ByteArray): ClientOpenFluxProviderStage.Snapshot? =
        ClientOpenFluxProviderStage().run {
            offer(line)
            finish(gracefulExit = true, readerDrained = true)
        }

    private fun rejected(line: String) = assertNull(read(bytes(line)))

    @Test fun realAllFalseRecordIsReportedWithExactlyFifteenFalseEvents() {
        val snapshot = requireNotNull(read(bytes(record())))
        assertEquals(15, snapshot.events.size)
        assertEquals(fields.toSet(), snapshot.events.keys)
        assertTrue(snapshot.events.values.all { !it })
    }

    @Test fun everyKnownFlagKeepsItsOwnBooleanIncludingFailuresAndFallback() {
        for (field in fields) {
            val snapshot = requireNotNull(read(bytes(record(setOf(field)))))
            assertEquals(fields.associateWith { it == field }, snapshot.events)
        }
        assertTrue(requireNotNull(read(bytes(record(fields.toSet())))).events.values.all { it })
    }

    @Test fun nativeSortedKeysAndOtherKeyOrdersAreAcceptedWithJsonWhitespace() {
        val expected = fields.associateWith { it == "classic_frame_rejected" }
        for (order in listOf(fields.sorted(), fields.reversed())) {
            val line = record(setOf("classic_frame_rejected"), order)
                .replace("{", "\t{ ").replace(":", " \t: ").replace(",", " ,\t") + "\r\n"
            assertEquals(expected, requireNotNull(read(bytes(line))).events)
        }
    }

    @Test fun unknownNativeLinesAreIgnoredBeforeAndAfterCandidateWithoutForwardingText() {
        val reader = ClientOpenFluxProviderStage()
        val secret = "PRIVATE_ENDPOINT_TOKEN_CONTEXT"
        for (line in listOf("", secret, "error: $secret", "INFO " + record(), "MCRF_PROVIDER_STAGE_V2 $secret")) {
            assertEquals(Unit, reader.offer(bytes(line)))
        }
        reader.offer(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x80.toByte()))
        reader.offer(ByteArray(8192) { 'x'.code.toByte() })
        reader.offer(bytes(record(setOf("mailru_read_closed"))))
        reader.offer(bytes("native cleanup $secret"))
        val snapshot = requireNotNull(reader.finish(true, true))
        assertEquals(fields.associateWith { it == "mailru_read_closed" }, snapshot.events)
        assertFalse(snapshot.events.toString().contains(secret))
    }

    @Test fun prefixMustMatchAtByteZeroIncludingVersionAndFinalSpace() {
        val json = record().removePrefix(prefix)
        for (start in listOf("", " ", "\t", "\ufeff", "mcrf_provider_stage_v1 ",
            "MCRF_PROVIDER_STAGE_V1", "MCRF_PROVIDER_STAGE_V1\t", "MCRF_PROVIDER_STAGE_V2 ")) {
            rejected(start + json)
        }
        for (length in 0 until prefix.length) rejected(prefix.take(length))
    }

    @Test fun missingOrEmptyDiagnosticNeverBecomesAnAllFalseReport() {
        assertNull(ClientOpenFluxProviderStage().finish(true, true))
        rejected("")
        rejected(prefix)
        rejected(prefix + "{}")
        rejected(prefix + "null")
        rejected(prefix + "[]")
    }

    @Test fun malformedUtf8IsRejectedWithoutReplacementDecoding() {
        val head = bytes(prefix + "{\"")
        val tail = bytes(record().removePrefix(prefix + "{\""))
        val invalid = listOf(
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xc2.toByte()),
            byteArrayOf(0xe2.toByte(), 0x28, 0xa1.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xff.toByte())
        )
        for (value in invalid) {
            assertNull(read(head + value + tail))
            assertNull(read(bytes(record()) + value))
            assertNull(read(bytes(record().replaceFirst("false", "")) + value))
        }
    }

    @Test fun duplicateKeysAreRejectedEvenWhenTheyAgreeOrWouldOverwriteFalse() {
        val first = fields.first()
        rejected(record().replaceFirst("\"$first\":false", "\"$first\":false,\"$first\":false"))
        rejected(record().replaceFirst("\"$first\":false", "\"$first\":false,\"$first\":true"))
        rejected(record(setOf(first)).replaceFirst("\"$first\":true", "\"$first\":true,\"$first\":false"))
        rejected(record().dropLast(1) + ",\"$first\":false}")
        // Fifteen fields, but one is duplicated and another is absent.
        rejected(record().replace(fields.last(), first))
    }

    @Test fun extraAndUnknownKeysCannotEnterSnapshots() {
        rejected(record().dropLast(1) + ",\"PRIVATE_TOKEN\":true}")
        rejected(record().replace(fields.last(), "PRIVATE_ENDPOINT"))
        rejected(record().replace(fields.last(), fields.last().uppercase()))
        rejected(record(order = fields.dropLast(1)))
    }

    @Test fun nonBooleanValuesAreNeverCoercedForAnyField() {
        val values = listOf("\"false\"", "\"true\"", "0", "1", "-1", "0.0", "null", "TRUE", "False",
            "[]", "{}", "[false]", "NaN", "Infinity", "falsex", "truefalse")
        for (field in fields) for (value in values) {
            rejected(record().replace("\"$field\":false", "\"$field\":$value"))
        }
    }

    @Test fun escapedKeysAndEscapedDuplicateAliasesAreRejectedByNarrowGrammar() {
        val escaped = "\\u006d" + fields.first().drop(1)
        rejected(record().replace(fields.first(), escaped))
        rejected(record().dropLast(1) + ",\"$escaped\":true}")
        rejected(record().replace(fields.first(), "mailru\\u005fsocketio_ready"))
        rejected(record().replaceFirst("false", "\\u0066alse"))
    }

    @Test fun malformedFlatJsonAndNonJsonWhitespaceAreRejected() {
        val valid = record()
        for (line in listOf(
            valid.replace('"', '\''),
            valid.replaceFirst(":", "="),
            valid.replaceFirst(",", " "),
            valid.replaceFirst(":", "::"),
            valid.dropLast(1),
            valid.dropLast(1) + ",}",
            prefix + "[" + valid.removePrefix(prefix) + "]",
            valid.replaceFirst("false", "{\"nested\":false}"),
            valid.replaceFirst("false", "/*private*/false"),
            valid.replaceFirst("false", "false//private\n"),
            valid.replaceFirst("{", "\ufeff{"),
            valid.replaceFirst("{", "\u000b{"),
            valid + "\u0000", valid + "\u000c", valid + "\u00a0"
        )) rejected(line)
    }

    @Test fun trailingGarbageOrConcatenatedObjectsCannotBecomeFinalRecords() {
        for (suffix in listOf("PRIVATE_TOKEN", "{}", "false", ";", "\nPRIVATE_TOKEN", "\n" + record())) {
            rejected(record() + suffix)
        }
    }

    @Test fun exact1024ByteBoundaryIsAcceptedAndOneMoreByteIsRejected() {
        val source = bytes(record())
        assertTrue(source.size < 1024)
        val exact = source + ByteArray(1024 - source.size) { 0x20 }
        assertEquals(1024, exact.size)
        assertNotNull(read(exact))
        assertNull(read(exact + byteArrayOf(0x20)))
        // Count bytes, not characters; no UTF-8 decoding is done before the bound.
        val multibyte = record() + "\u00e9".repeat((1024 - source.size) / 2 + 1)
        assertTrue(multibyte.length <= 1024)
        assertTrue(bytes(multibyte).size > 1024)
        rejected(multibyte)
    }

    @Test fun oversizeDiagnosticPoisonsTheLifetimeInEitherOrder() {
        val oversized = bytes(record() + " ".repeat(1024))
        for (first in listOf(true, false)) {
            val reader = ClientOpenFluxProviderStage()
            if (first) reader.offer(oversized)
            reader.offer(bytes(record()))
            if (!first) reader.offer(oversized)
            reader.offer(bytes(record()))
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun secondRecordRejectsRatherThanMergingOverwritingOrPublishingTwice() {
        for (second in listOf(record(), record(fields.toSet()), prefix + "{}")) {
            val reader = ClientOpenFluxProviderStage()
            reader.offer(bytes(record(setOf("mailru_ack_write_ok"))))
            reader.offer(bytes(second))
            assertNull(reader.finish(true, true))
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun malformedRecordCannotBeRepairedByALaterValidRecord() {
        for (invalid in listOf(prefix, prefix + "{}", record().replaceFirst("false", "\"false\""))) {
            val reader = ClientOpenFluxProviderStage()
            reader.offer(bytes(invalid))
            reader.offer(bytes(record()))
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun stagedCandidateExposesNothingAndNeedsBothDeathAndDrainEvidence() {
        for (gracefulExit in listOf(false, true)) for (readerDrained in listOf(false, true)) {
            val reader = ClientOpenFluxProviderStage()
            val offered: Unit = reader.offer(bytes(record(setOf("classic_decrypt_ok"))))
            assertEquals(Unit, offered)
            val snapshot = reader.finish(gracefulExit, readerDrained)
            assertEquals(gracefulExit && readerDrained, snapshot != null)
            if (snapshot != null) assertTrue(snapshot.events.getValue("classic_decrypt_ok"))
        }
    }

    @Test fun startupFailureCanReportBeforeParentStopAfterItsOwnNormalExitOneAndDrain() {
        for (observedExitCode in listOf(0, 1)) {
            val reader = ClientOpenFluxProviderStage()
            reader.offer(bytes(record(setOf("mailru_auth_write_failed", "mailru_read_closed"))))
            // No parent STOP or ACK is supplied to the pure reader.
            val snapshot = requireNotNull(reader.finish(observedExitCode in 0..1, true))
            assertTrue(snapshot.events.getValue("mailru_auth_write_failed"))
            assertTrue(snapshot.events.getValue("mailru_read_closed"))
        }
    }

    @Test fun forcedExitAndMissingDrainNeverReportEvenWithAValidRecord() {
        for ((gracefulExit, readerDrained) in listOf(false to true, true to false, false to false)) {
            val reader = ClientOpenFluxProviderStage()
            reader.offer(bytes(record()))
            assertNull(reader.finish(gracefulExit, readerDrained))
            // A subsequent stop/join callback cannot upgrade a frozen failure.
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun finishPublishesOnlyOnceAndLateOffersCannotChangeReturnedSnapshot() {
        val reader = ClientOpenFluxProviderStage()
        reader.offer(bytes(record(setOf("mailru_ack_write_ok"))))
        val snapshot = requireNotNull(reader.finish(true, true))
        reader.offer(bytes(record(fields.toSet())))
        reader.offer(bytes(prefix + "{}"))
        assertNull(reader.finish(true, true))
        assertNull(reader.finish(false, false))
        assertEquals(fields.associateWith { it == "mailru_ack_write_ok" }, snapshot.events)
    }

    @Test fun lateOfferCannotTurnMissingOrUndrainedLifetimeIntoAReport() {
        for (readerDrained in listOf(false, true)) {
            val reader = ClientOpenFluxProviderStage()
            assertNull(reader.finish(true, readerDrained))
            reader.offer(bytes(record()))
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun newReaderHasNoEventsOrPoisonFromAnotherChildLifetime() {
        val previous = ClientOpenFluxProviderStage()
        previous.offer(bytes(record(setOf("classic_decrypt_failed"))))
        val oldSnapshot = requireNotNull(previous.finish(true, true))
        val current = ClientOpenFluxProviderStage()
        previous.offer(bytes(record(fields.toSet())))
        current.offer(bytes(record()))
        val newSnapshot = requireNotNull(current.finish(true, true))
        assertTrue(oldSnapshot.events.getValue("classic_decrypt_failed"))
        assertTrue(newSnapshot.events.values.all { !it })
        assertNull(ClientOpenFluxProviderStage().finish(true, true))
        val rejected = ClientOpenFluxProviderStage()
        rejected.offer(bytes(prefix + "{}"))
        assertNull(rejected.finish(true, true))
        assertNotNull(read(bytes(record())))
    }

    @Test fun overlappingReadersKeepTheirOwnCandidatesAndDuplicateState() {
        val first = ClientOpenFluxProviderStage()
        val second = ClientOpenFluxProviderStage()
        first.offer(bytes(record(setOf("mailru_ack_write_failed"))))
        second.offer(bytes(record(setOf("mailru_ack_write_ok"))))
        first.offer(bytes(record()))
        assertNull(first.finish(true, true))
        val snapshot = requireNotNull(second.finish(true, true))
        assertTrue(snapshot.events.getValue("mailru_ack_write_ok"))
        assertFalse(snapshot.events.getValue("mailru_ack_write_failed"))
    }

    @Test fun offeredByteArrayIsNotRetainedForLaterParsing() {
        val reader = ClientOpenFluxProviderStage()
        val source = bytes(record(setOf("classic_frame_accepted")))
        reader.offer(source)
        source.fill(0)
        val snapshot = requireNotNull(reader.finish(true, true))
        assertEquals(fields.associateWith { it == "classic_frame_accepted" }, snapshot.events)
    }

    @Test fun snapshotCopiesSourceMapAndRejectsMutationThroughMapOrEntryViews() {
        val source = fields.associateWith { false }.toMutableMap()
        val snapshot = ClientOpenFluxProviderStage.Snapshot(source)
        source[fields.first()] = true
        source["PRIVATE_TOKEN"] = true
        source.clear()
        assertEquals(fields.associateWith { false }, snapshot.events)
        @Suppress("UNCHECKED_CAST")
        val mutable = snapshot.events as MutableMap<String, Boolean>
        assertThrows(UnsupportedOperationException::class.java) { mutable[fields.first()] = true }
        assertThrows(UnsupportedOperationException::class.java) { mutable.remove(fields.first()) }
        assertThrows(UnsupportedOperationException::class.java) { mutable.clear() }
        assertThrows(UnsupportedOperationException::class.java) { mutable.entries.first().setValue(true) }
        assertThrows(UnsupportedOperationException::class.java) { mutable.keys.clear() }
        assertEquals(fields.associateWith { false }, snapshot.events)
    }

    @Test fun simultaneousDuplicateOffersCannotLeaveAValidCandidate() {
        val reader = ClientOpenFluxProviderStage()
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val offers = (1..2).map {
                workers.submit(Callable {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    reader.offer(bytes(record()))
                })
            }
            start.countDown()
            offers.forEach { it.get(5, TimeUnit.SECONDS) }
            assertNull(reader.finish(true, true))
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun concurrentFinishCallsPublishASnapshotOnlyOnce() {
        val reader = ClientOpenFluxProviderStage()
        reader.offer(bytes(record()))
        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val finishes = (1..4).map {
                workers.submit(Callable {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    reader.finish(true, true)
                })
            }
            start.countDown()
            val snapshots = finishes.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(1, snapshots.size)
            assertEquals(fields.associateWith { false }, snapshots.single().events)
        } finally {
            workers.shutdownNow()
        }
    }
}
