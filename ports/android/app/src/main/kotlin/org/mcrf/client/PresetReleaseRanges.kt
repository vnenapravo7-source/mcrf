package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.zip.Inflater
import javax.net.ssl.HttpsURLConnection

/** Ranges contain raw DEFLATE entries from a published, read-only release asset. */
internal object PresetReleaseRanges {
    private data class Entry(val start: Long, val compressedBytes: Int, val sha256: String)
    private data class Source(val url: URL, val length: Long, val entries: Map<String, Entry>)

    @Volatile private var cached: Source? = null

    private fun source(context: Context): Source {
        cached?.let { return it }
        val manifest = context.assets.open("presets/release-ranges.json").use {
            JSONObject(String(ImportIO.read(it), Charsets.UTF_8))
        }
        val url = URL(manifest.getString("url"))
        require(url.protocol == "https" && url.host == "github.com" && url.userInfo == null)
        val length = manifest.getLong("length")
        require(length in 1..64L * 1024 * 1024)
        val rows = manifest.getJSONObject("presets")
        val entries = rows.keys().asSequence().associateWith { id ->
            val row = rows.getJSONArray(id)
            Entry(row.getLong(0), row.getInt(1), row.getString(2)).also {
                require(it.start in 1 until length && it.compressedBytes in 1..8_388_608 &&
                    it.start + it.compressedBytes <= length && it.sha256.matches(Regex("[0-9a-f]{64}")))
            }
        }
        return Source(url, length, entries).also { cached = it }
    }

    fun downloadable(context: Context, item: PresetAssets.Item): Boolean =
        !BuildConfig.OFFLINE_EDITION && source(context).entries[item.id]?.sha256 == item.sha256

    fun download(context: Context, item: PresetAssets.Item): ByteArray {
        require(downloadable(context, item))
        val source = source(context)
        val entry = source.entries.getValue(item.id)
        val end = entry.start + entry.compressedBytes - 1
        var url = source.url
        repeat(4) {
            val connection = RemoteRoutingNetwork.open(context, url) as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("Range", "bytes=${entry.start}-$end")
                connection.setRequestProperty("Accept-Encoding", "identity")
                when (connection.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Missing release redirect")
                        url = URL(url, location)
                        require(url.protocol == "https" && url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com") &&
                            url.userInfo == null && url.port in setOf(-1, 443))
                    }
                    206 -> {
                        require(connection.getHeaderField("Content-Range") == "bytes ${entry.start}-$end/${source.length}")
                        val compressed = connection.inputStream.use { input ->
                            val bytes = ByteArray(entry.compressedBytes)
                            val deadline = System.nanoTime() + 20_000_000_000L
                            var offset = 0
                            while (offset < bytes.size) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                if (System.nanoTime() >= deadline) throw IOException("Release download timed out")
                                val count = input.read(bytes, offset, bytes.size - offset)
                                require(count > 0)
                                offset += count
                            }
                            require(input.read() == -1)
                            bytes
                        }
                        return inflate(compressed, item.bytes.toInt()).also {
                            require(PresetAssets.sha256(it) == item.sha256) { "Контрольная сумма списка не совпадает." }
                        }
                    }
                    else -> throw IOException("Release range unavailable")
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Too many release redirects")
    }

    internal fun inflate(compressed: ByteArray, expectedBytes: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            // The extra byte detects output exceeding the trusted catalogue length.
            val output = ByteArray(expectedBytes + 1)
            var offset = 0
            while (!inflater.finished() && offset < output.size) {
                val count = inflater.inflate(output, offset, output.size - offset)
                require(count > 0 || inflater.finished())
                offset += count
            }
            require(inflater.finished() && inflater.remaining == 0 && offset == expectedBytes)
            return output.copyOf(expectedBytes)
        } finally { inflater.end() }
    }
}
