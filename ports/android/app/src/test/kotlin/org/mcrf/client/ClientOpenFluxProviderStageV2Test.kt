package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ClientOpenFluxProviderStageV2Test {
    private val prefix = "MCRF_PROVIDER_STAGE_V2 "
    private val v1Prefix = "MCRF_PROVIDER_STAGE_V1 "
    private val v1Fields = listOf(
        "mailru_socketio_ready", "mailru_auth_write_ok", "mailru_auth_write_failed",
        "mailru_auth_changes_seen", "mailru_ack_write_ok", "mailru_ack_write_failed",
        "mailru_fallback_ready", "mailru_chat_write_ok", "mailru_chat_write_failed",
        "mailru_peer_chat_decoded", "mailru_read_closed", "classic_decrypt_ok",
        "classic_decrypt_failed", "classic_frame_accepted", "classic_frame_rejected"
    )
    private val newFields = listOf(
        "raw_callback_missing", "demux_empty", "demux_session", "demux_classic",
        "layer_callback_missing", "codec_unknown", "codec_decode_failed", "codec_no_payload",
        "classic_header_invalid", "classic_direction_invalid", "classic_replay_rejected"
    )
    private val fields = v1Fields + newFields

    private fun record(enabled: Set<String> = emptySet(), order: List<String> = fields): String =
        prefix + order.joinToString(",", "{", "}") { "\"$it\":${it in enabled}" }

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)

    private fun read(line: ByteArray): ClientOpenFluxProviderStage.Snapshot? =
        ClientOpenFluxProviderStage.v2().run {
            offer(line)
            finish(gracefulExit = true, readerDrained = true)
        }

    private fun rejected(line: String) = assertNull(read(bytes(line)))

    @Test fun allFalseRecordHasExactlyTheReviewedTwentySixKeys() {
        val snapshot = requireNotNull(read(bytes(record())))
        assertEquals(26, snapshot.events.size)
        assertEquals(fields, snapshot.events.keys.toList())
        assertTrue(snapshot.events.values.all { !it })
    }

    @Test fun eachOfTheTwentySixFlagsKeepsItsOwnBoolean() {
        for (field in fields) {
            val snapshot = requireNotNull(read(bytes(record(setOf(field)))))
            assertEquals(fields.associateWith { it == field }, snapshot.events)
        }
        assertEquals(fields.associateWith { true }, requireNotNull(read(bytes(record(fields.toSet())))).events)
    }

    @Test fun sortedReversedAndRotatedKeysPreserveValuesAndCanonicalSnapshotOrder() {
        val enabled = setOf("mailru_auth_write_failed", "demux_classic", "classic_replay_rejected")
        for (order in listOf(fields.sorted(), fields.reversed(), fields.drop(9) + fields.take(9))) {
            val snapshot = requireNotNull(read(bytes(record(enabled, order))))
            assertEquals(fields.associateWith { it in enabled }, snapshot.events)
            assertEquals(fields, snapshot.events.keys.toList())
        }
    }

    @Test fun largestUnpaddedRecordIs777BytesAndAcceptsJsonWhitespace() {
        val source = record()
        assertEquals(777, bytes(source).size)
        assertEquals(778, bytes(source + "\n").size)
        assertNotNull(read(bytes(source + "\n")))
        val padded = source.replace("{", "\t{ ").replace(":", " \t: ")
            .replace(",", " ,\t") + " \r\n"
        assertTrue(bytes(padded).size <= 1024)
        assertEquals(fields.associateWith { false }, requireNotNull(read(bytes(padded))).events)
    }

    @Test fun exact1024ByteLimitIncludesWhitespaceAndLineTerminator() {
        val source = bytes(record())
        val exact = source + ByteArray(1024 - source.size - 2) { 0x20 } + bytes("\r\n")
        assertEquals(1024, exact.size)
        assertNotNull(read(exact))
        assertNull(read(exact + byteArrayOf(0x20)))
        val multibyte = record() + "\u00e9".repeat((1024 - source.size) / 2 + 1)
        assertTrue(multibyte.length <= 1024)
        assertTrue(bytes(multibyte).size > 1024)
        rejected(multibyte)
    }

    @Test fun versionsNeverAcceptEachOthersPrefixOrSchema() {
        val v1Record = v1Prefix + record(order = v1Fields).removePrefix(prefix)
        rejected(v1Record)
        val v1 = ClientOpenFluxProviderStage()
        v1.offer(bytes(record()))
        assertNull(v1.finish(true, true))
        // A correct prefix cannot disguise the other version's field set.
        rejected(prefix + v1Record.removePrefix(v1Prefix))
        val wrongSchemaV1 = ClientOpenFluxProviderStage()
        wrongSchemaV1.offer(bytes(v1Prefix + record().removePrefix(prefix)))
        assertNull(wrongSchemaV1.finish(true, true))
        val unchangedV1 = ClientOpenFluxProviderStage()
        unchangedV1.offer(bytes(v1Record))
        assertEquals(v1Fields.associateWith { false }, requireNotNull(unchangedV1.finish(true, true)).events)
    }

    @Test fun wrongVersionLinesRemainUnrecognizedAndCannotSupplyAnyEvents() {
        val v2 = ClientOpenFluxProviderStage.v2()
        val v1Record = v1Prefix + record(fields.toSet(), v1Fields).removePrefix(prefix)
        v2.offer(bytes(v1Record))
        v2.offer(bytes(record(setOf("demux_empty"))))
        v2.offer(bytes(v1Record))
        assertEquals(fields.associateWith { it == "demux_empty" }, requireNotNull(v2.finish(true, true)).events)
        val v1 = ClientOpenFluxProviderStage()
        v1.offer(bytes(record(fields.toSet())))
        v1.offer(bytes(v1Record))
        v1.offer(bytes(record(fields.toSet())))
        assertEquals(v1Fields.associateWith { true }, requireNotNull(v1.finish(true, true)).events)
    }

    @Test fun prefixMatchesOnlyExactAsciiVersionAndSpaceAtByteZero() {
        val json = record().removePrefix(prefix)
        for (start in listOf("", " ", "\t", "\ufeff", "mcrf_provider_stage_v2 ",
            "MCRF_PROVIDER_STAGE_V2", "MCRF_PROVIDER_STAGE_V2\t", "MCRF_PROVIDER_STAGE_V3 ")) {
            rejected(start + json)
        }
        for (length in 0 until prefix.length) rejected(prefix.take(length))
    }

    @Test fun absentEmptyAndNonObjectRecordsAreNotInventedAllFalseSnapshots() {
        assertNull(ClientOpenFluxProviderStage.v2().finish(true, true))
        for (line in listOf("", prefix, prefix + "{}", prefix + "null", prefix + "[]")) rejected(line)
    }

    @Test fun everyOriginalOrNewKeyIsRequired() {
        for (missing in fields) rejected(record(order = fields.filterNot { it == missing }))
        rejected(record(order = v1Fields))
        rejected(record(order = newFields))
    }

    @Test fun extraOrUnknownKeysAreRejectedRatherThanExposed() {
        rejected(record().dropLast(1) + ",\"private_endpoint\":true}")
        for (field in listOf(v1Fields.first(), newFields.last())) {
            rejected(record().replace(field, "private_token"))
            rejected(record().replace(field, field.uppercase()))
        }
    }

    @Test fun duplicateOriginalAndNewKeysRejectAgreeingConflictingAndSameCountAliases() {
        for (field in fields) {
            rejected(record().replaceFirst("\"$field\":false", "\"$field\":false,\"$field\":false"))
            rejected(record().replaceFirst("\"$field\":false", "\"$field\":false,\"$field\":true"))
            rejected(record(setOf(field)).replaceFirst("\"$field\":true", "\"$field\":true,\"$field\":false"))
        }
        rejected(record().replace(newFields.last(), v1Fields.first()))
        rejected(record().replace(v1Fields.last(), newFields.first()))
    }

    @Test fun escapedNamesIncludingUnicodeDuplicateAliasesAreRejected() {
        for (field in fields) {
            val escaped = "\\u00" + field.first().code.toString(16) + field.drop(1)
            rejected(record().replace(field, escaped))
            rejected(record().dropLast(1) + ",\"$escaped\":true}")
        }
        rejected(record().replace("demux_empty", "demux\\u005fempty"))
        rejected(record().replaceFirst("false", "\\u0066alse"))
    }

    @Test fun onlyLiteralLowercaseAsciiBooleansAreAcceptedForEveryField() {
        val invalid = listOf("\"true\"", "\"false\"", "0", "1", "-1", "0.0", "null", "TRUE", "False",
            "[]", "{}", "[false]", "NaN", "Infinity", "falsex", "truefalse", "ｆａｌｓｅ")
        for (field in fields) for (value in invalid) {
            rejected(record().replace("\"$field\":false", "\"$field\":$value"))
        }
    }

    @Test fun invalidUtf8CannotBeReplacedIntoAKeyValueOrTrailingWhitespace() {
        val invalid = listOf(
            byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xc2.toByte()), byteArrayOf(0xe2.toByte(), 0x28, 0xa1.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), byteArrayOf(0xff.toByte())
        )
        val valid = bytes(record())
        val keyAt = record().indexOf(newFields.first())
        val valueAt = record().indexOf("false")
        for (value in invalid) {
            assertNull(read(valid.copyOfRange(0, keyAt) + value + valid.copyOfRange(keyAt, valid.size)))
            assertNull(read(valid.copyOfRange(0, valueAt) + value + valid.copyOfRange(valueAt, valid.size)))
            assertNull(read(valid + value))
        }
        // Valid non-ASCII UTF-8 is also outside this fixed ASCII grammar.
        rejected(record().replace("demux_empty", "demux_émpty"))
        rejected(record() + "\u00a0")
    }

    @Test fun malformedJsonCommentsNonJsonWhitespaceAndTrailingContentAreRejected() {
        val valid = record()
        for (line in listOf(
            valid.replace('"', '\''), valid.replaceFirst(":", "="), valid.replaceFirst(",", " "),
            valid.replaceFirst(":", "::"), valid.dropLast(1), valid.dropLast(1) + ",}",
            prefix + "[" + valid.removePrefix(prefix) + "]",
            valid.replaceFirst("false", "{\"nested\":false}"),
            valid.replaceFirst("false", "/*private*/false"), valid.replaceFirst("false", "false//private\n"),
            valid.replaceFirst("{", "\ufeff{"), valid.replaceFirst("{", "\u000b{"),
            valid + "\u0000", valid + "\u000c", valid + "PRIVATE_TOKEN", valid + "{}",
            valid + "\n" + valid
        )) rejected(line)
    }

    @Test fun secondRecordPermanentlyRejectsInsteadOfMergingOrReplacing() {
        for (second in listOf(record(), record(fields.toSet()), prefix + "{}")) {
            val reader = ClientOpenFluxProviderStage.v2()
            reader.offer(bytes(record(setOf("demux_empty"))))
            reader.offer(bytes(second))
            reader.offer(bytes(record()))
            assertNull(reader.finish(true, true))
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun malformedOrOversizeRecognizedRecordCannotBeRepairedInEitherOrder() {
        for (invalid in listOf(prefix, prefix + "{}", record().replaceFirst("false", "\"false\""),
            record() + " ".repeat(1024))) {
            for (invalidFirst in listOf(true, false)) {
                val reader = ClientOpenFluxProviderStage.v2()
                if (invalidFirst) reader.offer(bytes(invalid))
                reader.offer(bytes(record()))
                if (!invalidFirst) reader.offer(bytes(invalid))
                reader.offer(bytes(record()))
                assertNull(reader.finish(true, true))
            }
        }
    }

    @Test fun unrecognizedPrivateTextInvalidBytesAndLargeLinesAreIgnoredWithoutExposure() {
        val reader = ClientOpenFluxProviderStage.v2()
        val privateText = "PRIVATE_ENDPOINT_TOKEN_CONTEXT"
        for (line in listOf("", privateText, "error: $privateText", "INFO " + record(), v1Prefix + privateText)) {
            assertEquals(Unit, reader.offer(bytes(line)))
        }
        reader.offer(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0x80.toByte()))
        reader.offer(ByteArray(8192) { 'x'.code.toByte() })
        reader.offer(bytes(record(setOf("classic_header_invalid"))))
        reader.offer(bytes("cleanup $privateText"))
        val snapshot = requireNotNull(reader.finish(true, true))
        assertEquals(fields.associateWith { it == "classic_header_invalid" }, snapshot.events)
        assertFalse(snapshot.events.toString().contains(privateText))
    }

    @Test fun offerExposesNoPrematureSnapshotAndFinishRequiresDeathAndEofEvidence() {
        for (normalExit in listOf(false, true)) for (drained in listOf(false, true)) {
            val reader = ClientOpenFluxProviderStage.v2()
            val offered: Unit = reader.offer(bytes(record(setOf("demux_session"))))
            assertEquals(Unit, offered)
            val snapshot = reader.finish(normalExit, drained)
            assertEquals(normalExit && drained, snapshot != null)
            if (snapshot != null) assertTrue(snapshot.events.getValue("demux_session"))
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun onlyCallerObservedNormalExitZeroOrOneWithMatchingDrainedReaderMayReport() {
        for (exitCode in listOf(-1, 0, 1, 2, 137)) for (forced in listOf(false, true)) {
            for (matchingChildEof in listOf(false, true)) {
                val reader = ClientOpenFluxProviderStage.v2()
                reader.offer(bytes(record(setOf("mailru_read_closed", "mailru_auth_write_failed"))))
                // Existing API carries caller evidence; this pure parser cannot observe a process or child ID.
                val normalExit = !forced && exitCode in 0..1
                val snapshot = reader.finish(normalExit, matchingChildEof)
                assertEquals(!forced && exitCode in 0..1 && matchingChildEof, snapshot != null)
                if (snapshot != null) assertTrue(snapshot.events.getValue("mailru_auth_write_failed"))
                assertNull(reader.finish(true, true))
            }
        }
    }

    @Test fun finishOnceFreezesPublishedSnapshotAndRejectsReuseAfterLateRecords() {
        val reader = ClientOpenFluxProviderStage.v2()
        reader.offer(bytes(record(setOf("classic_replay_rejected"))))
        val snapshot = requireNotNull(reader.finish(true, true))
        reader.offer(bytes(record(fields.toSet())))
        reader.offer(bytes(prefix + "{}"))
        assertNull(reader.finish(true, true))
        assertNull(reader.finish(false, false))
        assertEquals(fields.associateWith { it == "classic_replay_rejected" }, snapshot.events)
    }

    @Test fun lateRecordCannotUpgradeMissingForcedOrUndrainedFinishedLifetime() {
        for (offerBefore in listOf(false, true)) for ((normal, eof) in listOf(false to false, false to true,
            true to false, true to true)) {
            if (offerBefore && normal && eof) continue
            val reader = ClientOpenFluxProviderStage.v2()
            if (offerBefore) reader.offer(bytes(record()))
            assertNull(reader.finish(normal, eof))
            reader.offer(bytes(record(fields.toSet())))
            assertNull(reader.finish(true, true))
        }
    }

    @Test fun freshFactoriesAndOverlappingReadersNeverShareCandidatesOrPoison() {
        val first = ClientOpenFluxProviderStage.v2()
        val second = ClientOpenFluxProviderStage.v2()
        first.offer(bytes(record(setOf("demux_empty"))))
        second.offer(bytes(record(setOf("codec_unknown"))))
        first.offer(bytes(record()))
        assertNull(first.finish(true, true))
        val snapshot = requireNotNull(second.finish(true, true))
        assertEquals(fields.associateWith { it == "codec_unknown" }, snapshot.events)
        assertNull(ClientOpenFluxProviderStage.v2().finish(true, true))
        assertEquals(fields.associateWith { false }, requireNotNull(read(bytes(record()))).events)
    }

    @Test fun inputBytesAreNotRetainedAndReturnedSnapshotIsImmutable() {
        val reader = ClientOpenFluxProviderStage.v2()
        val source = bytes(record(setOf("demux_classic")))
        reader.offer(source)
        source.fill(0)
        val snapshot = requireNotNull(reader.finish(true, true))
        assertEquals(fields.associateWith { it == "demux_classic" }, snapshot.events)
        @Suppress("UNCHECKED_CAST")
        val mutable = snapshot.events as MutableMap<String, Boolean>
        assertThrows(UnsupportedOperationException::class.java) { mutable["private_token"] = true }
        assertThrows(UnsupportedOperationException::class.java) { mutable.entries.first().setValue(true) }
        assertThrows(UnsupportedOperationException::class.java) { mutable.keys.clear() }
    }

    @Test fun simultaneousDuplicateOffersCannotLeaveAReportableCandidate() {
        val reader = ClientOpenFluxProviderStage.v2()
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

    @Test fun concurrentFinishesPublishExactlyOneV2Snapshot() {
        val reader = ClientOpenFluxProviderStage.v2()
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
