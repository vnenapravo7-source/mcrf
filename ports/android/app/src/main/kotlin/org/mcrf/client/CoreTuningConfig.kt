package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Field mappings verified against Leadaxe/sing-box-lx v1.14.2-lx.11 option structs.
 * Never emits qThrone-only tls_tricks, custom fragment dialer fields or Xray mux. */
object CoreTuningConfig {
    val limitations = listOf(
        "Custom ClientHello fragmentation (size/sleep): установленный libbox не содержит qThrone dialer extension. Нужен отдельный подписанный модуль ядра с этим расширением.",
        "TLS tricks / mixedcase SNI: отсутствует поле tls_tricks в lx.11. Требуется ядро с поддержкой; изменение SNI текстом не заменяет эту функцию.",
        "Xray multiplex и специфические Xray VLESS/XHTTP/REALITY: нужен отдельный загружаемый Xray-модуль, валидатор его конфигурации и защищённый loopback SOCKS bridge. В базовый APK модуль не добавлен.",
        "HTTP/2 окна/keepalive/max streams: применяются только к HTTP outbound через http_client v2; V2Ray http/grpc transport поддерживает лишь idle_timeout. MASQUE lx.11 не поддерживает эти QUIC окна."
    )
    /** Probe configuration only: same compatible outbound/CA defaults as the user session,
     * with no control/service listeners or NTP client. Existing isolated probe inbounds stay unchanged.
     * Select optional backend on the raw profile first: built-in defaults must not masquerade
     * as an explicit profile fragment choice and suppress custom fragmentation. */
    fun applyTransportOnly(source: JSONObject, settings: CoreTuningSettings): JSONObject {
        settings.validate()
        return apply(source,settings.copy(clashEnabled=false,apiEnabled=false,ntpEnabled=false)).also { config ->
            // Imported profile services can include other admin listeners, not just type=api.
            config.remove("services")
            config.optJSONObject("experimental")?.let { it.remove("v2ray_api"); it.remove("debug") }
        }
    }
    /** One original sing-box-compatible outbound for the optional TLS worker, AFTER backend
     * selection. Never call on an Xray-native object or on the base SOCKS bridge.
     * Custom/built-in fragmentation selection belongs to the optional adapter; no built-in
     * default is inserted here. CA stores, NTP and admin APIs are process-level, not outbound fields. */
    fun applyWorkerTransportDefaults(source: JSONObject, settings: CoreTuningSettings): JSONObject {
        settings.validate()
        require(source.optString("type") in setOf("vless","vmess","trojan","http","socks")) { "Worker defaults требуют исходный TCP TLS выход sing-box, не Xray JSON или QUIC." }
        require(source.optJSONObject("tls")?.optBoolean("enabled") == true) { "Worker defaults применяются к исходному TLS выходу до замены на SOCKS bridge." }
        val outbound = JSONObject(source.toString())
        val reality = outbound.optJSONObject("tls")?.optJSONObject("reality")?.optBoolean("enabled") == true
        applyOutbound(outbound,settings.copy(fragmentDefault=false,insecure=settings.insecure && !reality))
        return outbound
    }
    fun apply(source: JSONObject, settings: CoreTuningSettings): JSONObject {
        settings.validate(); val config = JSONObject(source.toString())
        val certificate = config.optJSONObject("certificate") ?: JSONObject()
        certificate.put("store",settings.certificateStore); config.put("certificate",certificate)
        // NTP updates the core clock, never Android's clock (requires root).
        if (settings.ntpEnabled) config.put("ntp",JSONObject().put("enabled",true).put("server",settings.ntpServer).put("server_port",settings.ntpPort).put("interval",settings.ntpInterval).put("write_to_system",false))
        else config.remove("ntp")
        val experimental = config.optJSONObject("experimental") ?: JSONObject()
        experimental.remove("clash_api")
        if (settings.clashEnabled) experimental.put("clash_api",JSONObject().put("external_controller","127.0.0.1:${settings.clashPort}").put("secret",settings.clashSecret).put("access_control_allow_origin",JSONArray().put("http://127.0.0.1:${settings.clashPort}")))
        if (experimental.length() > 0) config.put("experimental",experimental)
        val services = config.optJSONArray("services") ?: JSONArray(); val next = JSONArray()
        // A profile must not open its own admin listener despite an explicit disabled setting.
        repeat(services.length()) { i -> val item = services.getJSONObject(i); if (item.optString("type") != "api" && item.optString("tag") != "mcrf-control") next.put(item) }
        if (settings.apiEnabled) next.put(JSONObject().put("type","api").put("tag","mcrf-control").put("listen","127.0.0.1").put("listen_port",settings.apiPort).put("secret",settings.apiSecret).put("access_control_allow_origin",JSONArray().put("http://127.0.0.1:${settings.apiPort}")))
        if (next.length() > 0) config.put("services",next) else config.remove("services")
        config.optJSONArray("outbounds")?.let { list -> repeat(list.length()) { i -> applyOutbound(list.getJSONObject(i),settings) } }
        config.optJSONArray("http_clients")?.let { list -> repeat(list.length()) { i -> applyHttpClient(list.getJSONObject(i),settings) } }
        return config
    }
    private fun default(target: JSONObject, key: String, value: Any?) { if (!target.has(key) && value != null && value != "") target.put(key,value) }
    private fun applyOutbound(output: JSONObject, s: CoreTuningSettings) {
        val type = output.optString("type")
        applyTls(output,s,type !in setOf("hysteria","hysteria2","tuic","masque"))
        if (type in setOf("vmess","vless","trojan","shadowsocks")) {
            val mux = output.optJSONObject("multiplex") ?: if (s.muxDefault && output.optString("flow") != "xtls-rprx-vision") JSONObject().also { output.put("multiplex",it) } else null
            mux?.let { default(it,"enabled",s.muxDefault); if (it.optBoolean("enabled")) { default(it,"protocol",s.muxProtocol); default(it,"max_streams",s.muxConcurrency); default(it,"padding",s.muxPadding) } }
        }
        if (type in setOf("hysteria","hysteria2","tuic")) { applyH2(output,s); default(output,"initial_packet_size",s.initialPacketSize.takeIf { it > 0 }); if (s.disablePmtu) default(output,"disable_path_mtu_discovery",true) }
        output.optJSONObject("transport")?.takeIf { it.optString("type") in setOf("http","grpc") }?.let { default(it,"idle_timeout",s.idleTimeout) }
        output.optJSONObject("http_client")?.let { applyHttpClient(it,s) }
    }
    private fun applyTls(target: JSONObject, s: CoreTuningSettings, tcp: Boolean) {
        target.optJSONObject("tls")?.takeIf { it.optBoolean("enabled") }?.let { tls ->
            if (s.insecure) tls.put("insecure",true) else default(tls,"insecure",false)
            if (tcp && s.fragmentDefault) { default(tls,"fragment",true); if (tls.optBoolean("fragment")) default(tls,"fragment_fallback_delay",s.fragmentDelay) }
            if (tcp && s.fingerprint.isNotEmpty() && !tls.has("utls")) tls.put("utls",JSONObject().put("enabled",true).put("fingerprint",s.fingerprint))
        }
    }
    private fun applyHttpClient(client: JSONObject, s: CoreTuningSettings) {
        applyTls(client,s,client.optInt("version",2) != 3)
        if (client.optInt("version",2) in setOf(0,2,3)) applyH2(client,s)
        if (client.optInt("version",2) == 3) { default(client,"initial_packet_size",s.initialPacketSize.takeIf { it > 0 }); if (s.disablePmtu) default(client,"disable_path_mtu_discovery",true) }
    }
    private fun applyH2(target: JSONObject,s: CoreTuningSettings) {
        default(target,"idle_timeout",s.idleTimeout); default(target,"keep_alive_period",s.keepAlive)
        default(target,"stream_receive_window",s.streamWindow.toLongOrNull()); default(target,"connection_receive_window",s.connectionWindow.toLongOrNull())
        default(target,"max_concurrent_streams",s.maxStreams.takeIf { it > 0 })
    }
}
