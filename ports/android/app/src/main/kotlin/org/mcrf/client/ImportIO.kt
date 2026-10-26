package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

object ImportIO {
    @JvmStatic @Throws(IOException::class)
    fun readText(input: InputStream): String {
        val bytes = read(input)
        try {
            return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Файл импорта содержит некорректный UTF-8. Данные не добавлены.", error)
        } finally { bytes.fill(0) }
    }
    @JvmStatic @Throws(IOException::class)
    fun read(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val length = input.read(buffer)
            if (length < 0) return output.toByteArray()
            if (output.size() + length > ServerImport.MAX_BYTES) throw IOException("Импорт больше 4 МБ.")
            output.write(buffer, 0, length)
        }
    }
}
