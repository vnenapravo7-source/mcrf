package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Exact single-hop VLESS conversion. Unsupported source options fail rather than disappear. */
object XrayConfig {
    fun vision(xray: JSONObject): Boolean = xray.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
        ?.optJSONArray("users")?.optJSONObject(0)?.optString("flow")=="xtls-rprx-vision"
    private fun only(j: JSONObject,keys: Set<String>) { require(j.keys().asSequence().all { it in keys }) { "Параметры профиля требуют другого ядра; преобразование Xray не может сохранить их." } }
    fun fromSingBox(source: JSONObject): JSONObject {
        val out=JSONObject(source.toString());only(out,setOf("type","tag","server","server_port","uuid","flow","tls","transport"))
        require(out.getString("type")=="vless");require(out.getInt("server_port") in 1..65535)
        val user=JSONObject().put("id",out.getString("uuid")).put("encryption","none")
        out.optString("flow").takeIf { it.isNotEmpty() }?.let { user.put("flow",it) }
        val stream=JSONObject().put("network","raw").put("security","none")
        out.optJSONObject("transport")?.let { tr ->
            val type=tr.getString("type");only(tr,when(type){"xhttp"->setOf("type","host","path","mode","extra");"ws"->setOf("type","path","headers");"httpupgrade"->setOf("type","path","host","headers");"grpc"->setOf("type","service_name");else->emptySet()})
            stream.put("network",type)
            val mapped=JSONObject(tr.toString());mapped.remove("type")
            if(type=="grpc") {val name=mapped.getString("service_name");mapped.remove("service_name");mapped.put("serviceName",name)}
            stream.put(when(type){"xhttp"->"xhttpSettings";"ws"->"wsSettings";"httpupgrade"->"httpupgradeSettings";"grpc"->"grpcSettings";else->error("transport")},mapped)
        }
        out.optJSONObject("tls")?.let { tls ->
            only(tls,setOf("enabled","server_name","insecure","alpn","utls","reality"))
            if(tls.optBoolean("enabled")) {
                val reality=tls.optJSONObject("reality")?.takeIf { it.optBoolean("enabled") }
                val settings=JSONObject().put("serverName",tls.optString("server_name",out.getString("server")))
                tls.optJSONObject("utls")?.let { utls -> only(utls,setOf("enabled","fingerprint"));if(utls.optBoolean("enabled")) settings.put("fingerprint",utls.getString("fingerprint")) }
                if(reality!=null) {
                    only(reality,setOf("enabled","public_key","short_id"));require(!tls.optBoolean("insecure")) { "REALITY не использует insecure TLS." }
                    require(!tls.has("alpn")) { "Явный ALPN профиля нельзя перенести в Xray REALITY без потери параметра." }
                    settings.put("password",reality.getString("public_key")).put("shortId",reality.optString("short_id"));if(!settings.has("fingerprint"))settings.put("fingerprint","chrome")
                    stream.put("security","reality").put("realitySettings",settings)
                } else {
                    require(!tls.optBoolean("insecure")) { "Закреплённое ядро Xray удалило allowInsecure; этот TLS профиль требует проверки сертификата или certificate pins." }
                    settings.put("allowInsecure",tls.optBoolean("insecure"));tls.optJSONArray("alpn")?.let { settings.put("alpn",JSONArray(it.toString())) }
                    stream.put("security","tls").put("tlsSettings",settings)
                }
            }
        }
        return JSONObject().put("protocol","vless").put("settings",JSONObject().put("vnext",JSONArray().put(JSONObject()
            .put("address",out.getString("server")).put("port",out.getInt("server_port")).put("users",JSONArray().put(user)))))
            .put("streamSettings",stream)
    }
}
