package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Local TUN -> existing isolated sing-box TCP/IP stack -> protected physical sockets.
 * No server, saved profile, mixed listener, remote resolver or control API is introduced. */
object TrafficCaptureConfig {
    fun direct(): JSONObject = JSONObject()
        .put("log", JSONObject().put("disabled", true))
        .put("inbounds", JSONArray().put(JSONObject().put("type", "tun").put("tag", "capture-in")
            .put("address", JSONArray().put("172.28.249.1/30").put("fdfe:dcba:249::1/126"))
            .put("mtu", 1400).put("stack", "gvisor").put("auto_route", true)
            .put("strict_route", true).put("dns_mode", "hijack")))
        .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "capture-direct")))
        .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "physical-dns")))
            .put("final", "physical-dns").put("disable_cache", true))
        .put("route", JSONObject().put("auto_detect_interface", true).put("find_process", true)
            .put("final", "capture-direct").put("rules", JSONArray().put(JSONObject()
                .put("port", 53).put("action", "hijack-dns"))))

    /** Only a new ordinary connection is prepared. Never reload an active server for capture. */
    fun observeNewServer(config: JSONObject): JSONObject = JSONObject(config.toString()).apply {
        val route = optJSONObject("route") ?: JSONObject().also { put("route", it) }
        route.put("find_process", true)
    }
}
