package org.mcrf.client

import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/** Optional anonymous request count. Never a download or installation receipt. */
internal object DownloadSourceReporting {
    private val busy = AtomicBoolean(false)
    fun tryReport(asset: URI) {
        try {
            if (!BuildConfig.DOWNLOAD_SOURCE_REPORTING || asset.scheme != "https" || asset.host != "github.com" ||
                asset.userInfo != null || asset.port != -1 || asset.query != null || asset.fragment != null ||
                !asset.path.startsWith("/${EnginePackage.REPOSITORY}/releases/download/")) return
            // One daemon worker, no pending queue or retry, even when DNS stalls.
            if (!busy.compareAndSet(false, true)) return
            try {
                Thread({
                    try {
                        val connection = URI("https://vnenapravo7.ru/mcrf-downloads/v1/events").toURL().openConnection() as HttpsURLConnection
                        try {
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 1500; connection.readTimeout = 1500
                            connection.useCaches = false; connection.requestMethod = "POST"; connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "application/json")
                            val bytes = JSONObject().put("source", "update").put("platform", "android").put("asset_url", asset.toASCIIString()).toString().toByteArray(Charsets.UTF_8)
                            connection.setFixedLengthStreamingMode(bytes.size)
                            connection.outputStream.use { it.write(bytes) }
                            connection.responseCode // Body is deliberately ignored and never interpreted.
                        } finally { connection.disconnect() }
                    } catch (_: Exception) { /* The direct GitHub download proceeds independently. */ }
                    finally { busy.set(false) }
                }, "mcrf-download-report").apply { isDaemon = true }.start()
            } catch (_: Exception) { busy.set(false) }
        } catch (_: Exception) { /* Reporting setup is optional too. */ }
    }
}
