package org.mcrf.client

import org.json.JSONObject

/** A TV import keeps source identity and per-connection options separate. */
internal class TvTransferredProfile private constructor(
    val original: String,
    val options: JSONObject,
    val whitelists: Boolean
) {
    val name: String get() = options.getString("name")

    fun encode(): String = JSONObject()
        .put("version", 1)
        .put("original", original)
        .put("options", options)
        .put("whitelists", whitelists)
        .toString()

    fun parse(): ServerImport.Server {
        val base = ServerImport.read(original).single()
        val entry = ConnectionEdits.apply(base, options)
        return ServerSectionChoice.apply(entry, mapOf(entry.id to whitelists))
    }

    companion object {
        fun capture(entry: ServerImport.Server) =
            TvTransferredProfile(entry.original, ConnectionEdits.snapshot(entry), entry.whitelists)

        fun decode(payload: String): TvTransferredProfile {
            val json = JSONObject(payload)
            require(json.getInt("version") == 1)
            return TvTransferredProfile(json.getString("original"), json.getJSONObject("options"), json.getBoolean("whitelists")).also {
                require(it.name.isNotBlank())
                it.parse()
            }
        }
    }
}
