package org.mcrf.client

import org.json.JSONObject

/** An aggregate byte counter cannot distinguish output branches. */
internal object UsageServerAttribution {
    /** A routed session can have an identified main server while its bytes span several outputs. */
    fun connectedOutput(config: JSONObject, observed: String = ""): String? {
        val final = config.optJSONObject("route")?.optString("final").orEmpty()
        val node = output(config, final) ?: return null
        if (node.optString("type") !in groupTypes) return final.takeIf { isServer(node) }
        val members = node.optJSONArray("outbounds") ?: return null
        if (observed.isBlank() || (0 until members.length()).none { members.optString(it) == observed }) return null
        return observed.takeIf { output(config, it)?.let(::isServer) == true }
    }

    fun permits(config: JSONObject, final: String): Boolean {
        if (final != connectedOutput(config)) return false
        val rules = config.optJSONObject("route")?.optJSONArray("rules") ?: return true
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: return false
            val outbound = rule.optString("outbound")
            val action = rule.optString("action")
            if (outbound.isNotEmpty() && (outbound != final || action !in routeActions)) return false
            if (outbound.isEmpty() && action !in inspectionActions) return false
        }
        return true
    }

    private fun output(config: JSONObject, tag: String): JSONObject? {
        if (tag.isBlank()) return null
        return sequenceOf("outbounds", "endpoints").mapNotNull(config::optJSONArray)
            .flatMap { list -> (0 until list.length()).asSequence().mapNotNull(list::optJSONObject) }
            .firstOrNull { it.optString("tag") == tag }
    }

    private fun isServer(output: JSONObject): Boolean = output.optString("type") !in nonServerTypes

    private val groupTypes = setOf("selector", "urltest")
    private val nonServerTypes = groupTypes + setOf("", "direct", "block", "dns")
    private val routeActions = setOf("", "route")
    private val inspectionActions = setOf("sniff", "resolve", "route-options", "hijack-dns")
}
