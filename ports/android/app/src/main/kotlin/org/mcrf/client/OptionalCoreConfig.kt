package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

object OptionalCoreConfig {
    data class Request(val kind: String,val outbound: JSONObject,val certificateStore: String? = null,val serverDns:JSONObject?=null)
    /** Called on each selected outbound BEFORE the base core tuning pass. No source mutation. */
    fun prepare(source: JSONObject, settings: OptionalCoreSettings,serverDns:(JSONObject)->JSONObject?={null}): Request? {
        settings.validate();val out=JSONObject(source.toString());val type=out.optString("type")
        val tls=out.optJSONObject("tls");val tcpTls=tls?.optBoolean("enabled")==true && type in setOf("vless","vmess","trojan","http","socks")
        if (tcpTls) {
            if(settings.customFragmentDefault && !out.has("tls_fragment")) {
                // Explicit per-profile built-in fragmentation has priority.
                if(!tls!!.has("fragment")) out.put("tls_fragment",JSONObject().put("enabled",true).put("size",settings.fragmentSize).put("sleep",settings.fragmentSleep))
            }
            if(settings.mixedCaseSniDefault && !tls!!.has("tls_tricks")) tls.put("tls_tricks",JSONObject().put("mixedcase_sni",true))
        }
        val needsTlsCore=out.has("tls_fragment") || tls?.has("tls_tricks")==true
        val needsXray=type=="xray" || type=="vless" && settings.xrayVlessDefault
        require(!needsTlsCore || tcpTls) { "Custom fragment / TLS tricks требуют поддерживаемый TCP TLS выход." }
        require(!(needsTlsCore && needsXray)) { "Совместное применение Xray и custom fragment/TLS tricks пока не поддерживается; выбери одно ядро." }
        val dns=if(needsXray||needsTlsCore)serverDns(out)else null
        if(needsXray||needsTlsCore){
            require(!out.has("domain_resolver")||dns!=null){"DNS адреса сервера должен передаваться вместе с профилем, а не отбрасываться."}
            // The binding is now a self-contained, checked private context, not
            // an unresolved reference to a DNS tag in the parent process.
            out.remove("domain_resolver")
        }
        if(needsXray) {
            val xray=if(type=="xray") JSONObject(out.getJSONObject("outbound").toString()) else XrayConfig.fromSingBox(out)
            if(type=="xray" && xray.optJSONObject("streamSettings")?.optString("security") in setOf("tls","reality")) {
                require(!settings.customFragmentDefault && !settings.mixedCaseSniDefault) {"Совместное применение Xray и custom fragment/TLS tricks пока не поддерживается; выбери одно ядро."}
            }
            if(!xray.has("mux") && settings.xrayMuxDefault && !XrayConfig.vision(xray)) xray.put("mux",JSONObject().put("enabled",true).put("concurrency",settings.xrayMuxConcurrency))
            return Request("xray",xray,serverDns=dns)
        }
        if(needsTlsCore) {
            out.optJSONObject("tls_fragment")?.let { f ->
                // Explicit disabled field is meaningful; omit it from the worker
                // unless other TLS extension behavior still selects this core.
                if(!f.optBoolean("enabled")) out.remove("tls_fragment") else {
                    OptionalCoreSettings.range(f.getString("size"),1,16384);OptionalCoreSettings.range(f.getString("sleep"),0,100)
                }
            }
            return Request("tls-core",out,serverDns=dns)
        }
        return null
    }
    fun privateConfig(request: Request,port: Int,user: String,password: String): JSONObject {
        require(port in 1..65535 && user.toByteArray().size in 16..255 && password.toByteArray().size in 16..255)
        require(request.certificateStore==null || request.kind=="tls-core" && request.certificateStore in setOf("system","mozilla")) {"Этот optional core не поддерживает выбранное хранилище CA."}
        return JSONObject().put("api",1).put("socks","127.0.0.1:$port").put("socks_user",user).put("socks_pass",password).put("outbound",JSONObject(request.outbound.toString()))
            .also {request.certificateStore?.let {store -> it.put("certificate_store",store)};request.serverDns?.let {itDns->it.put("server_dns",JSONObject(itDns.toString()))}}
    }
    /** Replaces only the chosen tag; existing routing/DNS remain with base libbox. */
    fun bridgeOutbound(tag: String,port: Int,user: String,password: String,kind: String = "tls-core"): JSONObject {
        require(tag.isNotBlank());require(port in 1..65535)
        require(kind in setOf("xray","tls-core"))
        return JSONObject().put("type","socks").put("tag",tag).put("server","127.0.0.1").put("server_port",port)
            .put("version","5").put("username",user).put("password",password).also {if(kind=="tls-core")it.put("network","tcp")}
    }
}
