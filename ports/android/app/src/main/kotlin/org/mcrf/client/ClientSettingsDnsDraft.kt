package org.mcrf.client

import org.json.JSONObject

/** Compare visible values, not serialized key order or hidden provider metadata. */
class ClientSettingsDnsDraft(initialVisible: JSONObject) {
    private var committed = values(initialVisible)
    private fun values(value: JSONObject) = listOf("provider", "protocol", "custom").map {value.optString(it, "")}
    @Synchronized fun changed(value: JSONObject) = values(value) != committed
    @Synchronized fun saved(value: JSONObject) { committed = values(value) }
}
