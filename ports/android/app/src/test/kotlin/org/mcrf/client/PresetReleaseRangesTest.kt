package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class PresetReleaseRangesTest {
    @Test fun releaseRangePinsMatchBundledPresets() {
        fun asset(path: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/presets/$path")).use { it.readBytes() }
        val catalogue = JSONObject(asset("catalogue.json").toString(Charsets.UTF_8)).getJSONArray("presets")
        val byId = (0 until catalogue.length()).map(catalogue::getJSONObject).associateBy { it.getString("id") }
        val release = JSONObject(asset("release-ranges.json").toString(Charsets.UTF_8)).getJSONObject("presets")
        assertEquals(6, release.length())
        release.keys().forEach { id ->
            val bytes = asset("$id.srs")
            val metadata = byId.getValue(id)
            assertEquals(metadata.getInt("bytes"), bytes.size)
            assertEquals(metadata.getString("sha256"), release.getJSONArray(id).getString(2))
            assertEquals(metadata.getString("sha256"), PresetAssets.sha256(bytes))
        }
    }

    @Test fun rawDeflateAcceptsOnlyOneCompleteEntryOfExpectedLength() {
        val content = "routing preset".repeat(1024).toByteArray()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val buffer = ByteArrayOutputStream()
        try { DeflaterOutputStream(buffer, deflater).use { it.write(content) } }
        finally { deflater.end() }
        val compressed = buffer.toByteArray()
        assertArrayEquals(content, PresetReleaseRanges.inflate(compressed, content.size))
        assertThrows(IllegalArgumentException::class.java) { PresetReleaseRanges.inflate(compressed, content.size - 1) }
        assertThrows(IllegalArgumentException::class.java) { PresetReleaseRanges.inflate(compressed, content.size + 1) }
        assertThrows(IllegalArgumentException::class.java) { PresetReleaseRanges.inflate(compressed + 0.toByte(), content.size) }
    }
}
