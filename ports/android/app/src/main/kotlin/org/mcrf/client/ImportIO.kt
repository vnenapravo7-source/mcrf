package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

object ImportIO {
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
