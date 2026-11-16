package org.mcrf.client

import org.json.JSONObject

/** Explicit JSON profile, not an invented upstream sharing URI. */
internal object SlipstreamProfile {
    const val KIND="slipstream"
    const val REPOSITORY="Mygod/slipstream-rust"
    const val COMMIT="7de506b222c83ae97a19a618e33fe26899af1bd5"
    private val fields=setOf("type","name","domain","resolver","certificate","keep_alive_ms","username","password")
    fun compatible(kind:String?,repository:String?,commit:String?,contract:Int)=
        kind==KIND&&repository==REPOSITORY&&commit==COMMIT&&contract==1
    fun fromJson(source:String,raw:JSONObject):ServerImport.Server {
        require(source.toByteArray(Charsets.UTF_8).size<=65536) {"Профиль Slipstream больше 64 КБ."}
        require(raw.optString("type")==KIND&&raw.keys().asSequence().all {it in fields}&&fields.none {raw.has(it)&&raw.isNull(it)}) {"Неизвестное или пустое поле Slipstream."}
        val data=JSONObject(raw.toString())
        val name=data.optString("name","Slipstream")
        require(name.length in 1..256&&name.none(Char::isISOControl)) {"Название: 1–256 символов без управляющих знаков."}
        val domain=data.getString("domain")
        require(domain.length<=100&&domain.matches(Regex("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+"))) {"Slipstream: делегированный домен до 100 символов."}
        try {CarrierProfile.numericResolver(data.getString("resolver"))}catch(_:Exception){throw IllegalArgumentException("Резолвер Slipstream: IP:порт; IPv6 — [IP]:порт.")}
        val certificate=data.getString("certificate").trim()
        require(certificate.startsWith("-----BEGIN CERTIFICATE-----")&&certificate.endsWith("-----END CERTIFICATE-----")&&certificate.windowed(27).count {it=="-----BEGIN CERTIFICATE-----"}==1) {"Укажите один PEM-сертификат самого сервера, не набор центров сертификации."}
        val parsed=try {java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(certificate.byteInputStream())}catch(_:Exception){emptyList<java.security.cert.Certificate>()}
        require(parsed.size==1) {"Неверный PEM-сертификат Slipstream."}
        val keepAlive=if(data.has("keep_alive_ms")) {
            val value=data.get("keep_alive_ms")
            require(value is Number&&value.toDouble()==value.toInt().toDouble()) {"Интервал Slipstream должен быть целым числом миллисекунд."}
            value.toInt()
        } else 1000
        require(keepAlive==0||keepAlive in 400..60000) {"Интервал Slipstream: 0 (выключен) или 400–60000 мс."}
        val user=data.optString("username");val password=data.optString("password")
        require(user.isEmpty()==password.isEmpty()&&user.toByteArray(Charsets.UTF_8).size<=255&&password.toByteArray(Charsets.UTF_8).size<=255) {"Логин и пароль удалённого SOCKS задаются вместе, до 255 байт каждый."}
        data.put("name",name).put("certificate",certificate).put("keep_alive_ms",keepAlive)
        return ServerImport.Server(source,name,KIND,domain,53,null,null,null,data)
    }
    fun privateConfig(profile:JSONObject,port:Int,user:String,password:String):JSONObject {
        require(port in 1..65535)
        val saved=JSONObject(profile.toString()).apply {remove("probe_only");remove("outbound_tag")}
        val checked=requireNotNull(fromJson(saved.toString(),saved).carrier)
        return JSONObject().apply {
            fields.filter {it !in setOf("type","name")&&checked.has(it)}.forEach {put(it,checked.get(it))}
            put("socks","127.0.0.1:$port");put("socks_user",user);put("socks_pass",password)
        }
    }
}
