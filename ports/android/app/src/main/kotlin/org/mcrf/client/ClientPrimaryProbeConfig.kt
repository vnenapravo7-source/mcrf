package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Disposable native snapshots only; never mutate a saved profile or publish a user proxy. */
internal object ClientPrimaryProbeConfig {
    fun isolated(source: JSONObject, port: Int): JSONObject {
        require(port in 1..65535) { "PROBE_LISTENER_PORT" }
        return JSONObject(source.toString()).apply {
            remove("experimental")
            remove("services")
            remove("ntp")
            // Strict native proxy preflight requires an ingress. Its random loopback port and
            // per-run credentials stay private to this snapshot; tests use module URLTest.
            put("inbounds", JSONArray().put(JSONObject().put("type", "mixed")
                .put("tag", "mcrf-private-probe").put("listen", "127.0.0.1").put("listen_port", port)
                .put("users", JSONArray().put(JSONObject().put("username", UUID.randomUUID().toString())
                    .put("password", UUID.randomUUID().toString())))))
        }
    }

    fun carrier(source: JSONObject, tag: String, port: Int, username: String, password: String): JSONObject {
        require(port in 1..65535) { "PROBE_CARRIER_PORT" }
        val copy = JSONObject(source.toString())
        val outputs = copy.getJSONArray("outbounds")
        val nodes = (0 until outputs.length()).map { outputs.getJSONObject(it) }.filter { it.optString("tag") == tag }
        require(nodes.size == 1 && nodes.single().optString("type") == "socks" &&
            nodes.single().optString("server") == "127.0.0.1") { "PROBE_CARRIER_OUTBOUND" }
        nodes.single().put("server_port", port).put("username", username).put("password", password)
        return copy
    }
}
