package org.mcrf.client

import com.google.zxing.*
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.ByteBuffer

object QrCodec {
    @JvmStatic fun encode(text: String, size: Int): BitMatrix {
        require(text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= 2953 && size in 128..2048) { "Подключение слишком длинное для QR. Поделись ссылкой или файлом." }
        return try { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)) }
        catch (_: WriterException) { throw IllegalArgumentException("Подключение слишком длинное для QR. Поделись ссылкой или файлом.") }
    }
    @JvmStatic fun decode(width: Int, height: Int, luminance: ByteArray): String? {
        require(width in 1..4096 && height in 1..4096 && width.toLong()*height <= 8_388_608 && luminance.size == width*height)
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        return decodeSource(source)
    }

    @JvmStatic fun decodePixels(width: Int, height: Int, pixels: IntArray): String? {
        require(width in 1..4096 && height in 1..4096 && width.toLong()*height <= 8_388_608 && pixels.size == width*height)
        return decodeSource(RGBLuminanceSource(width, height, pixels))
    }

    /** Camera Y planes may include row padding, pixel stride and a non-zero buffer position. */
    @JvmStatic fun cameraLuminance(width: Int, height: Int, rowStride: Int, pixelStride: Int, buffer: ByteBuffer): ByteArray {
        require(width in 1..4096 && height in 1..4096 && width.toLong()*height <= 8_388_608)
        require(pixelStride > 0 && rowStride.toLong() >= (width-1L)*pixelStride+1)
        val plane = buffer.slice()
        require((height-1L)*rowStride+(width-1L)*pixelStride < plane.limit()) { "Неполный кадр камеры" }
        return ByteArray(width*height) { index -> plane.get((index/width)*rowStride+(index%width)*pixelStride) }
    }

    private fun decodeSource(source: LuminanceSource): String? {
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        for (candidate in listOf(source, source.invert())) {
            try { return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text }
            catch (_: ReaderException) { }
        }
        return null
    }
}
