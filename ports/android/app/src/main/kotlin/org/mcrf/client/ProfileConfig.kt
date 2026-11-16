package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

object ProfileConfig {
    const val DEFAULT_PORT = 10808
    const val PROBE_SECONDS = 60
    const val PROBE_TOLERANCE_MS = 50
    @JvmStatic fun build(source: String, tun: Boolean, port: Int): JSONObject {
        require(source.toByteArray(Charsets.UTF_8).size <= ServerImport.MAX_BYTES) { "Конфигурация больше 4 МБ." }
        require(port in 1024..65535) { "Порт должен быть от 1024 до 65535." }
        val config = JSONObject(source)
        config.remove("mcrf_carrier");config.remove("mcrf_bs")
        val outputs = config.optJSONArray("outbounds") ?: JSONArray()
        val endpoints = config.optJSONArray("endpoints")
        val tags = hashSetOf<String>(); var first: String? = null
        listOfNotNull(outputs, endpoints).forEach { list -> repeat(list.length()) { i ->
            val tag = list.getJSONObject(i).optString("tag", "")
            require(tag.trim().isNotEmpty() && tags.add(tag)) { "У выходов должны быть разные непустые теги." }
            if (first == null) first = tag
        } }
        require(tags.isNotEmpty()) { "В конфигурации нет выходов подключения." }
        val route = config.optJSONObject("route") ?: JSONObject()
        val finalTag = route.optString("final", "")
        if (finalTag.isEmpty()) route.put("final", first) else require(finalTag in tags) { "Выход route.final не найден." }
        validateAndroidRules(route, tun)
        repeat(outputs.length()) { i -> val output = outputs.getJSONObject(i)
            if (isLoopback(output.optString("server", ""))) {
                if (!output.has("inet4_bind_address")) output.put("inet4_bind_address", "127.0.0.1")
                if (!output.has("inet6_bind_address")) output.put("inet6_bind_address", "::1")
            }
        }
        var loopbackTag = "mcrf-loopback"; var index = 0
        while (loopbackTag in tags) loopbackTag = "mcrf-loopback-${++index}"
        outputs.put(JSONObject().put("type", "direct").put("tag", loopbackTag).put("inet4_bind_address", "127.0.0.1").put("inet6_bind_address", "::1"))
        config.put("outbounds", outputs)
        val rules = route.optJSONArray("rules") ?: JSONArray()
        val ordered = JSONArray().put(JSONObject().put("ip_cidr", JSONArray().put("127.0.0.0/8").put("::1/128")).put("action", "route").put("outbound", loopbackTag))
        repeat(rules.length()) { ordered.put(rules.get(it)) }
        route.put("rules", ordered).put("auto_detect_interface", true); config.put("route", route)
        val inbounds = JSONArray()
        if (tun) inbounds.put(JSONObject().put("type", "tun").put("tag", "tun-in").put("mtu", 1400)
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            .put("stack", "gvisor").put("auto_route", true).put("strict_route", true).put("dns_mode", "hijack"))
        inbounds.put(JSONObject().put("type", "mixed").put("tag", "local-proxy").put("listen", "127.0.0.1").put("listen_port", port))
        config.put("inbounds", inbounds)
        // No externally supplied admin/control listeners. Telemetry uses libbox's private IPC.
        config.remove("experimental")
        return config
    }
    private fun isLoopback(host: String): Boolean {
        if (host.equals("localhost", true) || host == "::1") return true
        return host.matches(Regex("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) && host.split('.').all { it.toInt() <= 255 }
    }
    private fun validateAndroidRules(value: Any, tun: Boolean) {
        when (value) {
            is JSONObject -> {
                require(listOf("process_path", "process_path_regex", "process_name").none(value::has)) { "Маршруты по процессам Linux/Windows нужно заменить списками приложений Android." }
                require(tun || !value.has("package_name")) { "Маршруты по приложениям работают только с системным VPN." }
                value.keys().forEach { validateAndroidRules(value.get(it), tun) }
            }
            is JSONArray -> repeat(value.length()) { validateAndroidRules(value.get(it), tun) }
        }
    }
}
