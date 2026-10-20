package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ClientOpenFluxProviderStageV3Test {
    private val prefix = "MCRF_PROVIDER_STAGE_V3 "
    private val v1Fields = listOf(
        "mailru_socketio_ready", "mailru_auth_write_ok", "mailru_auth_write_failed",
        "mailru_auth_changes_seen", "mailru_ack_write_ok", "mailru_ack_write_failed",
        "mailru_fallback_ready", "mailru_chat_write_ok", "mailru_chat_write_failed",
        "mailru_peer_chat_decoded", "mailru_read_closed", "classic_decrypt_ok",
        "classic_decrypt_failed", "classic_frame_accepted", "classic_frame_rejected"
    )
    private val v2Fields = v1Fields + listOf(
        "raw_callback_missing", "demux_empty", "demux_session", "demux_classic",
        "layer_callback_missing", "codec_unknown", "codec_decode_failed", "codec_no_payload",
        "classic_header_invalid", "classic_direction_invalid", "classic_replay_rejected"
    )
    private val sessionFields = listOf(
        "sess_hdr_bad", "sess_dir_bad", "sess_dec_fail", "sess_dec_ok", "sess_batch_bad",
        "sess_env_bad", "sess_role_bad", "sess_hello_ok", "sess_hello_bad", "sess_cb_missing", "sess_replay"
    )
    private val fields = v2Fields + sessionFields

    private fun record(enabled: Set<String> = emptySet(), order: List<String> = fields): String =
        prefix + order.joinToString(",", "{", "}") { "\"$it\":${it in enabled}" }

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)

    private fun read(line: ByteArray): ClientOpenFluxProviderStage.Snapshot? =
        ClientOpenFluxProviderStage.v3().run {
            offer(line)
            finish(gracefulExit = true, readerDrained = true)
        }

    // Grammar cases must reach parsing rather than merely exceed the byte bound.
    private fun rejectedWithinBound(line: String) {
        assertTrue("Grammar fixture exceeds 1024 bytes", bytes(line).size <= 1024)
        assertNull(read(bytes(line)))
    }

    @Test fun allFalseRecordHasExactlyThirtySevenCanonicalKeysAndExactNativeSizes() {
        val line = record()
        assertEquals(1016, bytes(line).size)
        assertTrue(bytes(line).all { it.toInt() in 0..127 })
        for ((suffix, expectedSize) in listOf("" to 1016, "\n" to 1017, "\r\n" to 1018)) {
            assertEquals(expectedSize, bytes(line + suffix).size)
            val snapshot = requireNotNull(read(bytes(line + suffix)))
            assertEquals(37, snapshot.events.size)
            assertEquals(fields, snapshot.events.keys.toList())
            assertEquals(fields.associateWith { false }, snapshot.events)
        }
    }

    @Test fun everyOriginalAndSessionFlagCanBeTrueIndependently() {
        for (field in fields) {
            val snapshot = requireNotNull(read(bytes(record(setOf(field)))))
            assertEquals(fields.associateWith { it == field }, snapshot.events)
        }
        val allTrue = record(fields.toSet())
        assertEquals(979, bytes(allTrue).size)
        assertEquals(fields.associateWith { true }, requireNotNull(read(bytes(allTrue))).events)
    }

    @Test fun sortedReversedAndRotatedKeysKeepValuesAndCanonicalOutputOrder() {
        val enabled = setOf("mailru_read_closed", "demux_session", "sess_dec_ok", "sess_hello_bad")
        for (order in listOf(fields.sorted(), fields.reversed(), fields.drop(26) + fields.take(26))) {
            val snapshot = requireNotNull(read(bytes(record(enabled, order))))
            assertEquals(fields.associateWith { it in enabled }, snapshot.events)
            assertEquals(fields, snapshot.events.keys.toList())
        }
    }

    @Test fun jsonWhitespaceWithinEightByteHeadroomAndExact1024BoundaryAreAccepted() {
        val source = record()
        val padded = source.replaceFirst("{", "\t { ").replaceFirst(":", " \t: ") + "\r\n"
        assertEquals(1024, bytes(padded).size)
        assertNotNull(read(bytes(padded)))
        val exact = bytes(source) + ByteArray(6) { 0x20 } + bytes("\r\n")
        assertEquals(1024, exact.size)
        assertNotNull(read(exact))
        assertNull(read(exact + byteArrayOf(0x20)))
        assertNull(read(bytes(padded + "\n")))
    }

    @Test fun oversizeIsMeasuredInBytesIncludingTerminatorsRatherThanCharacters() {
        assertNull(read(bytes(record() + " ".repeat(9))))
        val multibyte = record() + "é".repeat(5)
        assertEquals(1021, multibyte.length)
        assertEquals(1026, bytes(multibyte).size)
        assertNull(read(bytes(multibyte)))
        val source = bytes(record()) + ByteArray(8) { 0x20 }
        assertNotNull(read(source))
        assertNull(read(source + bytes("\n")))
    }

    @Test fun allThreeVersionsRejectOtherPrefixesAndSamePrefixOtherSchemas() {
        val contracts = listOf(v1Fields, v2Fields, fields)
        fun reader(version: Int) = when (version) {
            1 -> ClientOpenFluxProviderStage()
            2 -> ClientOpenFluxProviderStage.v2()
            else -> ClientOpenFluxProviderStage.v3()
        }
        for (version in 1..3) for (sourceVersion in 1..3) {
            val source = record(order = contracts[sourceVersion - 1]).removePrefix(prefix)
            val otherPrefix = "MCRF_PROVIDER_STAGE_V$sourceVersion " + source
            val matchingPrefix = "MCRF_PROVIDER_STAGE_V$version " + source
            for (line in listOf(otherPrefix, matchingPrefix)) {
                val parser = reader(version)
                parser.offer(bytes(line))
                assertEquals(version == sourceVersion, parser.finish(true, true) != null)
            }
        }
    }

    @Test fun otherVersionLinesDoNotSupplyEventsOrPoisonTheCorrectV3Record() {
        val reader = ClientOpenFluxProviderStage.v3()
        val older = listOf(1 to v1Fields, 2 to v2Fields).map { (version, keys) ->
            "MCRF_PROVIDER_STAGE_V$version " + record(keys.toSet(), keys).removePrefix(prefix)
        }
        older.forEach { reader.offer(bytes(it)) }
        reader.offer(bytes(record(setOf("sess_hdr_bad"))))
        older.forEach { reader.offer(bytes(it)) }
        assertEquals(fields.associateWith { it == "sess_hdr_bad" }, requireNotNull(reader.finish(true, true)).events)
    }

    @Test fun onlyExactAsciiPrefixAtByteZeroWithItsFinalSpaceIsRecognized() {
        val json = record().removePrefix(prefix)
        for (start in listOf("", " ", "\t", "\ufeff", "mcrf_provider_stage_v3 ",
            "MCRF_PROVIDER_STAGE_V3", "MCRF_PROVIDER_STAGE_V3\t", "MCRF_PROVIDER_STAGE_V4 ")) {
            assertNull(read(bytes(start + json)))
        }
        for (length in 0 until prefix.length) rejectedWithinBound(prefix.take(length))
    }

    @Test fun absentEmptyOrNonObjectRecordsNeverBecomeAllFalseReports() {
        assertNull(ClientOpenFluxProviderStage.v3().finish(true, true))
        for (line in listOf("", prefix, prefix + "{}", prefix + "null", prefix + "[]")) rejectedWithinBound(line)
    }

    @Test fun everyOneOfTheThirtySevenKeysIsRequired() {
        for (missing in fields) rejectedWithinBound(record(order = fields.filterNot { it == missing }))
        rejectedWithinBound(record(order = v2Fields))
        rejectedWithinBound(record(order = sessionFields))
    }

    @Test fun unknownExtraUppercaseAndRenamedKeysAreRejectedBelowTheSizeLimit() {
        val source = record(fields.toSet())
        rejectedWithinBound(source.dropLast(1) + ",\"private_endpoint\":true}")
        for (field in fields) {
            rejectedWithinBound(source.replace(field, "private_token"))
            rejectedWithinBound(source.replace(field, field.uppercase()))
        }
    }

    @Test fun duplicateAgreeingConflictingAndEqualCountAliasesAreRejectedWithinBound() {
        val source = record(fields.toSet())
        for (field in fields) {
            val pair = "\"$field\":true"
            rejectedWithinBound(source.replaceFirst(pair, "$pair,$pair"))
            rejectedWithinBound(source.replaceFirst(pair, "$pair,\"$field\":false"))
            rejectedWithinBound(source.replaceFirst(pair, "\"$field\":false,$pair"))
        }
        rejectedWithinBound(source.replace(sessionFields.last(), v1Fields.first()))
        rejectedWithinBound(source.replace(v1Fields.last(), sessionFields.first()))
    }

    @Test fun escapedNamesAndEscapedDuplicateAliasesAreRejectedWithinBound() {
        val source = record(fields.toSet())
        for (field in fields) {
            val escaped = "\\u00" + field.first().code.toString(16) + field.drop(1)
            rejectedWithinBound(source.replace(field, escaped))
            rejectedWithinBound(source.dropLast(1) + ",\"$escaped\":true}")
        }
        rejectedWithinBound(source.replace("sess_dec_ok", "sess\\u005fdec_ok"))
        rejectedWithinBound(source.replaceFirst("true", "\\u0074rue"))
    }

    @Test fun everyFieldRejectsAllNonLiteralOrNonAsciiBooleanValuesWithinBound() {
        val source = record(fields.toSet())
        val invalid = listOf("\"true\"", "\"false\"", "0", "1", "-1", "0.0", "null", "TRUE", "False",
            "[]", "{}", "[false]", "NaN", "Infinity", "falsex", "truefalse", "ｆａｌｓｅ")
        for (field in fields) for (value in invalid) {
            rejectedWithinBound(source.replace("\"$field\":true", "\"$field\":$value"))
        }
    }

    @Test fun malformedUtf8AtKeysValuesOrEndIsRejectedWithoutReplacement() {
        val source = record()
        val valid = bytes(source)
        val invalid = listOf(
            byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xc2.toByte()), byteArrayOf(0xe2.toByte(), 0x28, 0xa1.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), byteArrayOf(0xff.toByte())
        )
        for (position in listOf(source.indexOf("sess_dec_ok"), source.indexOf("false"), source.length)) {
            for (value in invalid) {
                val line = valid.copyOfRange(0, position) + value + valid.copyOfRange(position, valid.size)
                assertTrue(line.size <= 1024)
                assertNull(read(line))
            }
        }
        rejectedWithinBound(source.replace("sess_dec_ok", "séss_dec_ok"))
        rejectedWithinBound(source + "\u00a0")
    }

    @Test fun malformedObjectsCommentsNonJsonWhitespaceAndTrailingContentAreRejectedWithinBound() {
        val source = record(fields.toSet())
        for (line in listOf(
            source.replace('"', '\''), source.replaceFirst(":", "="), source.replaceFirst(",", " "),
            source.replaceFirst(":", "::"), source.dropLast(1), source.dropLast(1) + ",}",
            prefix + "[" + source.removePrefix(prefix) + "]",
            source.replaceFirst("true", "{\"nested\":true}"),
            source.replaceFirst("true", "/*private*/true"), source.replaceFirst("true", "true//private\n"),
            source.replaceFirst("{", "\ufeff{"), source.replaceFirst("{", "\u000b{"),
            source + "\u0000", source + "\u000c", source + "PRIVATE_TOKEN", source + "{}", source + "\nfalse"
        )) rejectedWithinBound(line)
        assertNull(read(bytes(source + "\n" + source)))
    }

    @Test fun secondRecordPermanentlyRejectsEvenIdenticalOrMalformedRecords() {
        for (second in listOf(record(), record(fields.toSet()), prefix + "{}")) {
            val reader = ClientOpenFluxProviderStage.v3()
            reader.offer(bytes(record(setOf("sess_batch_bad"))))
            reader.offer(bytes(second))
            reader.offer(bytes(record()))
            assertNull(reader.finish(true, true))
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun malformedOrOversizeRecordCannotBeRepairedBeforeOrAfterAValidCandidate() {
        for (invalid in listOf(prefix, prefix + "{}", record().replaceFirst("false", "null"), record() + " ".repeat(9))) {
            for (invalidFirst in listOf(true, false)) {
                val reader = ClientOpenFluxProviderStage.v3()
                if (invalidFirst) reader.offer(bytes(invalid))
                reader.offer(bytes(record()))
                if (!invalidFirst) reader.offer(bytes(invalid))
                reader.offer(bytes(record()))
                assertNull(reader.finish(true, true))
            }
        }
    }

    @Test fun unrelatedPrivateTextLargeLinesAndInvalidBytesAreNotExposed() {
        val reader = ClientOpenFluxProviderStage.v3()
        val secret = "PRIVATE_ENDPOINT_TOKEN_CONTEXT"
        for (line in listOf("", secret, "error: $secret", "INFO " + record())) reader.offer(bytes(line))
        reader.offer(ByteArray(8192) { 'x'.code.toByte() })
        reader.offer(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x80.toByte()))
        reader.offer(bytes(record(setOf("sess_dir_bad"))))
        reader.offer(bytes("cleanup $secret"))
        val snapshot = requireNotNull(reader.finish(true, true))
        assertEquals(fields.associateWith { it == "sess_dir_bad" }, snapshot.events)
        assertFalse(snapshot.events.toString().contains(secret))
    }

    @Test fun noPrematureSnapshotAndBothNormalDeathAndDrainedReaderEvidenceAreRequired() {
        for (normal in listOf(false, true)) for (eof in listOf(false, true)) {
            val reader = ClientOpenFluxProviderStage.v3()
            val offered: Unit = reader.offer(bytes(record(setOf("sess_dec_ok"))))
            assertEquals(Unit, offered)
            val snapshot = reader.finish(normal, eof)
            assertEquals(normal && eof, snapshot != null)
            if (snapshot != null) assertTrue(snapshot.events.getValue("sess_dec_ok"))
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun callerObservedExitZeroOrOneIsRequiredAndForcedOrWrongChildEofNeverReports() {
        for (code in listOf(-1, 0, 1, 2, 137)) for (forced in listOf(false, true)) {
            for (matchingChildEof in listOf(false, true)) {
                val reader = ClientOpenFluxProviderStage.v3()
                reader.offer(bytes(record(setOf("sess_hello_bad"))))
                // The existing API consumes caller evidence; it cannot observe a process or child ID itself.
                val snapshot = reader.finish(!forced && code in 0..1, matchingChildEof)
                assertEquals(!forced && code in 0..1 && matchingChildEof, snapshot != null)
                assertNull(reader.finish(true, true))
            }
        }
    }

    @Test fun finishOnceKeepsPublishedSnapshotFrozenAgainstLateRecordsAndReuse() {
        val reader = ClientOpenFluxProviderStage.v3()
        reader.offer(bytes(record(setOf("sess_replay"))))
        val snapshot = requireNotNull(reader.finish(true, true))
        reader.offer(bytes(record(fields.toSet())))
        reader.offer(bytes(prefix + "{}"))
        assertNull(reader.finish(true, true))
        assertNull(reader.finish(false, false))
        assertEquals(fields.associateWith { it == "sess_replay" }, snapshot.events)
    }

    @Test fun lateRecordsCannotUpgradeMissingForcedOrUndrainedFinishedLifetimes() {
        for (offerBefore in listOf(false, true)) for ((normal, eof) in listOf(false to false, false to true,
            true to false, true to true)) {
            if (offerBefore && normal && eof) continue
            val reader = ClientOpenFluxProviderStage.v3()
            if (offerBefore) reader.offer(bytes(record()))
            assertNull(reader.finish(normal, eof))
            reader.offer(bytes(record(fields.toSet())))
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun freshFactoriesAndOverlappingReadersHaveIndependentEventsAndPoison() {
        val first = ClientOpenFluxProviderStage.v3()
        val second = ClientOpenFluxProviderStage.v3()
        first.offer(bytes(record(setOf("sess_env_bad"))))
        second.offer(bytes(record(setOf("sess_hello_ok"))))
        first.offer(bytes(record()))
        assertNull(first.finish(true, true))
        assertEquals(fields.associateWith { it == "sess_hello_ok" }, requireNotNull(second.finish(true, true)).events)
        assertNull(ClientOpenFluxProviderStage.v3().finish(true, true))
        assertEquals(fields.associateWith { false }, requireNotNull(read(bytes(record()))).events)
    }

    @Test fun offeredBytesAreNotRetainedAndV3SnapshotIsImmutable() {
        val reader = ClientOpenFluxProviderStage.v3()
        val source = bytes(record(setOf("sess_cb_missing")))
        reader.offer(source)
        source.fill(0)
        val snapshot = requireNotNull(reader.finish(true, true))
        assertEquals(fields.associateWith { it == "sess_cb_missing" }, snapshot.events)
        @Suppress("UNCHECKED_CAST")
        val mutable = snapshot.events as MutableMap<String, Boolean>
        assertThrows(UnsupportedOperationException::class.java) { mutable["private_token"] = true }
        assertThrows(UnsupportedOperationException::class.java) { mutable.entries.first().setValue(true) }
        assertThrows(UnsupportedOperationException::class.java) { mutable.keys.clear() }
    }

    @Test fun simultaneousDuplicateOffersPermanentlyReject() {
        val reader = ClientOpenFluxProviderStage.v3()
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

    @Test fun concurrentFinishesPublishExactlyOneV3Snapshot() {
        val reader = ClientOpenFluxProviderStage.v3()
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
