package org.mcrf.client

import org.json.JSONObject

/** Transport defaults must run on the original outbound, before it becomes a SOCKS bridge. */
object OptionalCoreTuning {
    data class Prepared(val request: OptionalCoreConfig.Request?,val notes: List<String>)
    fun prepare(source: JSONObject,optional: OptionalCoreSettings,core: CoreTuningSettings):Prepared {
        core.validate();val tuned=JSONObject(source.toString());val type=tuned.optString("type")
        tuned.optJSONObject("tls")?.takeIf {it.optBoolean("enabled") && type in setOf("vless","vmess","trojan","http","socks")}?.let {tls ->
            // Matches CoreTuningConfig: explicit fingerprint wins, global insecure
            // true overrides certificate checking deliberately when selected.
            if(core.insecure && tls.optJSONObject("reality")?.optBoolean("enabled")!=true)tls.put("insecure",true)
            if(core.fingerprint.isNotEmpty() && !tls.has("utls")) tls.put("utls",JSONObject().put("enabled",true).put("fingerprint",core.fingerprint))
        }
        val raw=OptionalCoreConfig.prepare(tuned,optional) ?: return Prepared(null,emptyList())
        val request=if(raw.kind=="tls-core" && core.certificateStore=="mozilla") raw.copy(certificateStore="mozilla") else raw
        val notes=mutableListOf<String>()
        if(core.certificateStore!="system" && request.kind=="xray")notes.add("Xray использует system CA store и certificate pins; Mozilla CA store поддерживает базовое ядро и TLS core.")
        if(core.ntpEnabled)notes.add("NTP базового libbox не меняет часы optional core; синхронизация optional core пока не реализована.")
        if(core.fragmentDefault && request.kind=="xray")notes.add("Встроенный fragment sing-box не добавляется в Xray; выбранное Xray ядро не поддерживает этот default.")
        if(core.fragmentDefault && request.kind=="tls-core" && request.outbound.optJSONObject("tls_fragment")?.optBoolean("enabled")!=true) {
            request.outbound.optJSONObject("tls")?.let {tls ->
                if(!tls.has("fragment")){tls.put("fragment",true);tls.put("fragment_fallback_delay",core.fragmentDelay)}
            }
        }
        if(request.kind=="xray" && core.muxDefault)notes.add("Xray использует отдельные defaults Xray MUX; sing-box multiplex не переносится в другой протокол.")
        if(request.kind=="tls-core" && core.muxDefault && request.outbound.optString("type") in setOf("vless","vmess","trojan") && request.outbound.optString("flow")!="xtls-rprx-vision" && !request.outbound.has("multiplex")) {
            request.outbound.put("multiplex",JSONObject().put("enabled",true).put("protocol",core.muxProtocol).put("max_streams",core.muxConcurrency).put("padding",core.muxPadding))
        }
        // Native Xray input also needs its TLS defaults, as it bypasses conversion.
        if(type=="xray")request.outbound.optJSONObject("streamSettings")?.let { stream ->
            if(stream.optString("security")=="tls") {
                require(!stream.has("tlsSettings") || stream.opt("tlsSettings") is JSONObject) {"Xray tlsSettings должен быть объектом."}
                val tls=stream.optJSONObject("tlsSettings") ?: JSONObject().also {stream.put("tlsSettings",it)}
                require(!core.insecure && !tls.optBoolean("allowInsecure")) {"Закреплённое ядро Xray удалило allowInsecure; отключите пропуск TLS-проверки или используйте поддерживаемые certificate pins."}
                if(core.fingerprint.isNotEmpty() && !tls.has("fingerprint"))tls.put("fingerprint",core.fingerprint)
            }
            if(stream.optString("security")=="reality")stream.optJSONObject("realitySettings")?.let {reality ->
                if(core.fingerprint.isNotEmpty() && !reality.has("fingerprint"))reality.put("fingerprint",core.fingerprint)
            }
        }
        if(request.kind=="xray" && request.outbound.optJSONObject("streamSettings")?.optString("security")=="tls") {
            require(!request.outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings").optBoolean("allowInsecure")) {"Закреплённое ядро Xray удалило allowInsecure; отключите пропуск TLS-проверки или используйте certificate pins."}
        }
        return Prepared(request,notes)
    }
}
