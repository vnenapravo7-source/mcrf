package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object DnsConfig {
    // A valid native tag identifies DNS emitted by the app, not an imported JSON
    // profile. No unknown metadata is passed to the native core or backup validator.
    const val APP_DNS_TAG = "mcrf-app-dns"
    fun appOwned(config:JSONObject):Boolean = config.optJSONObject("dns")?.let { dns ->
        dns.optString("final")==APP_DNS_TAG && dns.optJSONArray("servers")?.let { servers ->
            (0 until servers.length()).any {servers.optJSONObject(it)?.optString("tag")==APP_DNS_TAG}
        }==true
    }==true
    const val PROFILE_DNS_MARKER = "mcrf_profile_dns"
    /** Import/build owner sets this only when it has emitted a validated selected profile DNS section. */
    @JvmStatic fun markProfileDns(config:JSONObject):JSONObject {
        require(config.optJSONObject("dns")?.optJSONArray("servers")?.length()?.let {it>0}==true){"Профиль не содержит DNS-серверов."}
        return config.put(PROFILE_DNS_MARKER,true)
    }
    /** Consume app metadata before passing any generated object to the native core. */
    @JvmStatic fun consumeProfileDns(config:JSONObject):Boolean {
        val use=config.optBoolean(PROFILE_DNS_MARKER,false);config.remove(PROFILE_DNS_MARKER)
        if(use)require(config.optJSONObject("dns")?.optJSONArray("servers")?.length()?.let {it>0}==true){"DNS выбранного профиля повреждён."}
        return use
    }
    @JvmField val PROVIDERS = arrayOf("Cloudflare", "Google", "Quad9", "AdGuard", "Яндекс", "Comss.one", "Свой", "Системный")
    @JvmStatic fun defaults(): JSONObject = JSONObject().put("provider", "Cloudflare").put("protocol", "DoH").put("custom", "")
    @JvmStatic fun protocols(provider: String): Array<String> = when (provider) {
        "Системный" -> arrayOf("Системный", "UDP")
        "Cloudflare" -> arrayOf("UDP", "DoT", "DoH", "DoH3")
        "Google", "Яндекс" -> arrayOf("UDP", "DoT", "DoH")
        "AdGuard" -> arrayOf("UDP", "DoT", "DoH", "DoQ")
        "Quad9", "Comss.one", "Свой" -> arrayOf("UDP", "DoT", "DoH", "DoH3", "DoQ")
        else -> emptyArray()
    }
    @JvmStatic fun build(settings: JSONObject, tag: String, bootstrap: String): JSONObject {
        val provider = settings.getString("provider"); val protocol = settings.getString("protocol")
        require(protocol in protocols(provider)) { "Этот DNS-провайдер не поддерживает выбранный протокол." }
        if (provider == "Системный") return JSONObject().put("type", "local").put("tag", tag)
        var (host, server) = when (provider) {
            "Cloudflare" -> "cloudflare-dns.com" to "1.1.1.1"
            "Google" -> "dns.google" to "8.8.8.8"
            "Quad9" -> "dns.quad9.net" to "9.9.9.9"
            "AdGuard" -> "dns.adguard-dns.com" to "94.140.14.14"
            "Яндекс" -> "common.dot.dns.yandex.net" to "77.88.8.8"
            "Comss.one" -> "dns.comss.one" to "195.133.25.16"
            else -> "" to ""
        }
        val type = when (protocol) { "DoT" -> "tls"; "DoQ" -> "quic"; "DoH3" -> "h3"; "DoH" -> "https"; else -> "udp" }
        var port = when (type) { "udp" -> 53; "tls", "quic" -> 853; else -> 443 }
        var path = "/dns-query"
        if (provider == "Свой") {
            var raw = settings.optString("custom", "").trim()
            if (!raw.contains("://")) raw = "$type://" + if (raw.contains(':') && RouteRules.literal(raw) != null) "[$raw]" else raw
            val uri = try { URI(raw) } catch (_: Exception) { throw IllegalArgumentException("Некорректный адрес своего DNS.") }
            require(type == uri.scheme && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null) {
                "Адрес DNS должен совпадать с выбранным протоколом, без пароля и параметров."
            }
            host = uri.host.replace("[", "").replace("]", ""); server = host
            require(uri.port == -1 || uri.port in 1..65535) { "Порт DNS: от 1 до 65535." }
            if (uri.port > 0) port = uri.port
            val uriPath = uri.rawPath.orEmpty()
            if (type in setOf("https", "h3")) { if (uriPath.isNotEmpty() && uriPath != "/") path = uriPath }
            else require(uriPath.isEmpty() || uriPath == "/") { "Для этого протокола DNS путь не используется." }
        }
        require(port in 1..65535 && !host.contains('%')) { "Некорректный адрес DNS." }
        return JSONObject().put("type", type).put("tag", tag).put("server", server).put("server_port", port).apply {
            if (type in setOf("https", "h3")) put("path", path)
            if (type != "udp") put("tls", JSONObject().put("enabled", true).put("server_name", host))
            if (RouteRules.literal(server) == null) put("domain_resolver", bootstrap)
        }
    }
    /** Native DNS transports reject a detour to a bare direct outbound at startup.
     * Omitting it uses the normal protected physical dialer. Keep explicit/custom
     * dialer options and proxy tags rather than silently changing their policy. */
    fun applyDetour(server:JSONObject,config:JSONObject,tag:String):JSONObject {
        if(server.optString("type")=="local")return server
        val outbounds=config.optJSONArray("outbounds")
        val outbound=(0 until (outbounds?.length()?:0)).map {outbounds!!.getJSONObject(it)}.firstOrNull {it.optString("tag")==tag}
        val bareDirect=outbound!=null&&outbound.optString("type")=="direct"&&outbound.keys().asSequence().all {it in setOf("type","tag")}
        if(bareDirect)server.remove("detour")else server.put("detour",tag)
        return server
    }
    @JvmStatic fun applyGenerated(config: JSONObject, settings: JSONObject) {
        if(consumeProfileDns(config)||CompatibilityImportDns.hasOverride(config))return
        config.put("dns", JSONObject().put("servers", JSONArray().put(build(settings, APP_DNS_TAG, "bootstrap"))
            .put(JSONObject().put("type", "local").put("tag", "bootstrap"))).put("final", APP_DNS_TAG))
        config.optJSONObject("route")?.let { route ->
            when(val resolver=route.opt("default_domain_resolver")) {
                "chosen" -> route.put("default_domain_resolver",APP_DNS_TAG)
                is JSONObject -> if(resolver.optString("server")=="chosen")resolver.put("server",APP_DNS_TAG)
            }
        }
    }
}
