package org.mcrf.client

import org.json.JSONObject

/** Only live groups need an IPC query. A concrete final outbound cannot switch. */
internal object OutboundObservation {
    fun fixed(config:JSONObject?,target:String):String? {
        if(config==null||target.isEmpty())return null
        val outputs=config.optJSONArray("outbounds")?:return null
        val node=(0 until outputs.length()).mapNotNull {outputs.optJSONObject(it)}
            .firstOrNull {it.optString("tag")==target}?:return null
        return if(node.optString("type") in setOf("urltest","selector"))null else target
    }
}
