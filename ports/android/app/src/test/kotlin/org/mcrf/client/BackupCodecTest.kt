package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    @Test fun portableRoundTripAndDifferentNonces() {
        val text = "Приватный-профиль + / = & ".repeat(100).toByteArray(Charsets.UTF_8)
        val password = "fixture-password".toCharArray(); val first = BackupCodec.encrypt(text, password); val second = BackupCodec.encrypt(text, password)
        assertArrayEquals(text, BackupCodec.decrypt(first, password)); assertFalse(first.contentEquals(second)); assertFalse(String(first, Charsets.ISO_8859_1).contains("fixture-password"))
    }
    @Test fun wrongPasswordNeverReturnsPlaintext() { val bytes = BackupCodec.encrypt("private".toByteArray(), "fixture-password".toCharArray()); try { BackupCodec.decrypt(bytes, "different-password".toCharArray()); fail() } catch (error: IllegalArgumentException) { assertFalse(error.message!!.contains("private")) } }
    @Test fun corruptedCiphertextIsRejected() { val bytes = BackupCodec.encrypt("private".toByteArray(), "fixture-password".toCharArray()); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); try { BackupCodec.decrypt(bytes, "fixture-password".toCharArray()); fail() } catch (_: IllegalArgumentException) { } }
    @Test fun forgedKdfIsRejectedBeforeExpensiveWork() { val bytes = ByteArray(56); "MCRFBK01".toByteArray().copyInto(bytes); java.nio.ByteBuffer.wrap(bytes).putInt(8, Int.MAX_VALUE); try { BackupCodec.decrypt(bytes, "fixture-password".toCharArray()); fail() } catch (_: IllegalArgumentException) { } }
    @Test(expected = IllegalArgumentException::class) fun shortPasswordRejected() { BackupCodec.encrypt(ByteArray(1), "short".toCharArray()) }
    @Test(expected = IllegalArgumentException::class) fun oversizedInputIsBounded() { BackupCodec.read(java.io.ByteArrayInputStream(ByteArray(BackupCodec.MAX_FILE+1))) }
}
