package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class QrCodecTest {
    @Test fun qrPreservesSecretsUnicodeAndPlusSigns() {
        val source = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&path=%2B#Москва"
        val matrix = QrCodec.encode(source, 512)
        val luminance = ByteArray(512*512) { i -> if (matrix[i%512, i/512]) 0 else 255.toByte() }
        assertEquals(source, QrCodec.decode(512, 512, luminance))
    }
    @Test fun blankImageIsNotAConnection() {
        assertNull(QrCodec.decode(128, 128, ByteArray(128*128) { 255.toByte() }))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedQrIsRejected() {
        QrCodec.encode("secret".repeat(1000), 512)
    }
    @Test fun invertedCameraFrameIsAccepted() {
        val source = "https://example.com/subscription?token=fixture"
        val matrix = QrCodec.encode(source, 384)
        val image = ByteArray(384*384) { i -> if (matrix[i%384, i/384]) 255.toByte() else 0 }
        assertEquals(source, QrCodec.decode(384, 384, image))
    }
    @Test fun imagePixelsPreserveUnicodeAndParameters() {
        val source = "https://example.com/subscription?name=%D0%A2&token=abc+def#Москва"
        val matrix = QrCodec.encode(source, 384)
        val image = IntArray(384*384) { i -> if (matrix[i%384, i/384]) 0xff000000.toInt() else 0xffffffff.toInt() }
        assertEquals(source, QrCodec.decodePixels(384, 384, image))
    }
    @Test fun rotatedCameraQrPreservesConnection() {
        val source = "trojan://fixture@example.com:443?security=tls#Сервер"
        val matrix = QrCodec.encode(source, 256)
        val image = ByteArray(256*256) { i -> if (matrix[255-i/256, i%256]) 0 else 255.toByte() }
        assertEquals(source, QrCodec.decode(256, 256, image))
    }
    @Test fun paddedCameraPlaneUsesBufferPositionAndBothStrides() {
        val buffer = java.nio.ByteBuffer.wrap(byteArrayOf(99, 98, 1, 90, 2, 90, 3, 90, 90, 90, 4, 90, 5, 90, 6))
        buffer.position(2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), QrCodec.cameraLuminance(3, 2, 8, 2, buffer))
        assertEquals(2, buffer.position())
    }
    @Test(expected = IllegalArgumentException::class) fun incompleteCameraPlaneIsRejected() {
        QrCodec.cameraLuminance(3, 2, 8, 2, java.nio.ByteBuffer.wrap(ByteArray(12)))
    }
    @Test(expected = IllegalArgumentException::class) fun incorrectPixelCountIsRejected() {
        QrCodec.decodePixels(256, 256, IntArray(10))
    }
    @Test fun qrCapacityFailureHasActionableMessage() {
        try { QrCodec.encode("a".repeat(2600), 512); fail("Medium correction cannot fit this UTF-8 payload") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("Поделись ссылкой или файлом")) }
    }
}
