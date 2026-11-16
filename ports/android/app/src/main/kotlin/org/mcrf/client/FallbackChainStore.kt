package org.mcrf.client

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic, bounded, no-backup sidecar. Only opaque catalogue IDs and toggles, no profiles. */
class FallbackChainStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "fallback-chain-v1.json"))
    data class Settings(val enabled: Boolean = false, val vpn: List<String> = emptyList(), val bs: List<String> = emptyList()) {
        fun ids(whitelists: Boolean) = if (whitelists) bs else vpn
    }
    fun load(): Settings = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized Settings()
        val bytes = file.openRead().use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            require(count <= MAX_BYTES); buffer.copyOf(count)
        }
        decode(String(bytes, Charsets.UTF_8))
    }
    fun save(settings: Settings) = synchronized(lock) {
        val bytes = encode(settings).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 8192
        internal fun decode(source: String): Settings {
            require(source.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val token = org.json.JSONTokener(source)
            val json = token.nextValue() as JSONObject
            require(token.nextClean() == '\u0000' && json.getInt("schema") == 1)
            fun ids(key: String): List<String> {
                val array = json.getJSONArray(key)
                require(array.length() <= FallbackChainPolicy.MAX_ENTRIES)
                return (0 until array.length()).map { array.getString(it) }.also { require(FallbackChainPolicy.valid(it)) }
            }
            return Settings(json.getBoolean("enabled"), ids("vpn"), ids("bs"))
        }
        internal fun encode(settings: Settings): String {
            require(FallbackChainPolicy.valid(settings.vpn) && FallbackChainPolicy.valid(settings.bs))
            return JSONObject().put("schema", 1).put("enabled", settings.enabled)
                .put("vpn", JSONArray(settings.vpn)).put("bs", JSONArray(settings.bs)).toString()
                .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
        }
    }
}
