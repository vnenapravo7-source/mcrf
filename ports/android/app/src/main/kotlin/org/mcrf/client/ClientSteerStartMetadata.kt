package org.mcrf.client

import org.json.JSONObject

/** Bounded generation reply, not a capability or native traffic/STOP proof.
 * Both service and owner independently bind the selected port to the SAME
 * immutable settings. No credentials, addresses or arbitrary log strings. */
internal object ClientSteerStartMetadata {
    private fun isV2(settings: JSONObject): Boolean {
        val value = settings.opt("steerVersion") ?: 1
        require((value is Int || value is Long) && (value as Number).toLong() in 1L..2L) { "STEER_START_MODEL" }
        return (value as Number).toLong() == 2L
    }
    private fun checked(value: Any?, settings: JSONObject): Int {
        require(value is Int) { "STEER_PROXY_PORT_TYPE" }
        val proxy = settings.optJSONObject("localProxy")
        if (proxy == null) require(value == 0) { "STEER_UNREQUESTED_PROXY" }
        else {
            val expected = proxy.get("port")
            require(expected is Int || expected is Long) { "STEER_PROXY_PORT_TYPE" }
            val port = (expected as Number).toLong()
            require(port == 0L || port in 1024L..65535L) { "STEER_PROXY_PORT_RANGE" }
            require(value in 1024..65535 && (port == 0L || value.toLong() == port)) { "STEER_PROXY_PORT_MISMATCH" }
        }
        return value
    }
    fun moduleReason(reason: String, settings: JSONObject): Int {
        if (!isV2(settings)) { require(reason.isEmpty()) { "STEER_LEGACY_METADATA" }; return 0 }
        require(reason.length in 1..128) { "STEER_START_METADATA_SIZE" }
        val json = JSONObject(reason)
        require(json.keys().asSequence().toSet() == setOf("schema", "localProxyPort") && json.opt("schema") == 1) { "STEER_START_METADATA_SCHEMA" }
        return checked(json.get("localProxyPort"), settings)
    }
    fun workerPort(value: Any?, settings: JSONObject): Int {
        if (!isV2(settings)) { require(value == null || value == 0) { "STEER_LEGACY_METADATA" }; return 0 }
        return checked(value, settings)
    }
}
