package org.mcrf.client

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

internal object SstpProfile {
    const val KIND="sstp"
    const val REPOSITORY="ttrroyy/qThroneMobile"
    const val COMMIT="8463d3397b0a8522f92811f5c0698679ab8efc77"
    private val fields=setOf("type","name","server","server_port","username","password","sni","ca_certificate","allow_insecure")
    fun compatible(kind:String?,repository:String?,commit:String?,contract:Int)=kind==KIND&&repository==REPOSITORY&&commit==COMMIT&&contract==1
    private fun decode(value:String)=URLDecoder.decode(value.replace("+","%2B"),"UTF-8")
    private fun host(value:String)=value.isNotEmpty()&&value.length<=253&&(RouteRules.literal(value)!=null||value.split('.').all {it.matches(Regex("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))})
    fun link(source:String):ServerImport.Server {
        val uri=URI(source)
        require(uri.scheme.equals(KIND,true)&&uri.host!=null&&uri.rawPath.orEmpty() in setOf("","/")) {"SSTP: sstp://логин:пароль@сервер:443?sni=домен#название"}
        val credentials=uri.rawUserInfo.orEmpty().split(':',limit=2)
        require(credentials.size==2) {"Укажите логин и пароль SSTP."}
        val raw=JSONObject().put("type",KIND).put("server",uri.host.removePrefix("[").removeSuffix("]")).put("server_port",if(uri.port==-1)443 else uri.port)
            .put("username",decode(credentials[0])).put("password",decode(credentials[1]))
        uri.rawFragment?.let {raw.put("name",decode(it).ifEmpty {"SSTP"})}
        val seen=mutableSetOf<String>()
        uri.rawQuery?.takeIf(String::isNotEmpty)?.split('&')?.forEach {part->
            val pair=part.split('=',limit=2);val key=decode(pair[0])
            require(pair.size==2&&key in setOf("sni","insecure")&&seen.add(key)) {"Неизвестный или повторный параметр ссылки SSTP."}
            val value=decode(pair[1])
            if(key=="insecure")require(value in setOf("0","false")) {"Проверку сертификата SSTP отключать нельзя. Для своего сертификата добавьте доверенный CA в редакторе."}
            else raw.put(key,value)
        }
        return fromJson(source,raw)
    }
    fun fromJson(source:String,raw:JSONObject):ServerImport.Server {
        require(source.toByteArray(Charsets.UTF_8).size<=65536&&raw.optString("type")==KIND&&raw.keys().asSequence().all {it in fields}&&fields.none {raw.has(it)&&raw.isNull(it)}) {"Неизвестное, пустое или слишком большое поле SSTP."}
        if(raw.has("allow_insecure"))require(raw.get("allow_insecure")==false) {"Проверка сертификата SSTP обязательна; добавьте доверенный CA вместо отключения проверки."}
        val data=JSONObject(raw.toString()).apply {remove("allow_insecure")}
        val name=data.optString("name","SSTP")
        require(name.length in 1..256&&name.none(Char::isISOControl)) {"Название: 1–256 символов без управляющих знаков."}
        val server=data.getString("server");val sni=data.optString("sni")
        require(host(server)&&(sni.isEmpty()||host(sni))) {"Адрес SSTP и SNI указываются без схемы, порта и пути."}
        val port=if(data.has("server_port")){val n=data.get("server_port");require(n is Number&&n.toDouble()==n.toInt().toDouble());n.toInt()}else 443
        require(port in 1..65535) {"Порт SSTP: 1–65535."}
        val user=data.getString("username");val password=data.getString("password")
        require(user.isNotEmpty()&&user.toByteArray(Charsets.UTF_8).size<=256&&user.none {it=='\r'||it=='\n'||it=='\u0000'}&&password.isNotEmpty()&&password.toByteArray(Charsets.UTF_8).size<=4096) {"Укажите логин и пароль SSTP без управляющих знаков в логине."}
        val ca=data.optString("ca_certificate")
        if(ca.isNotEmpty()) {
            val parsed=try {java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(ca.byteInputStream())}catch(_:Exception){emptyList<java.security.cert.Certificate>()}
            require(parsed.isNotEmpty()) {"Неверный PEM-сертификат CA SSTP."}
        }
        data.put("name",name).put("server_port",port)
        return ServerImport.Server(source,name,KIND,server,port,null,null,null,data)
    }
    fun privateConfig(profile:JSONObject,port:Int,user:String,password:String):JSONObject {
        require(port in 1..65535)
        val clean=JSONObject(profile.toString()).apply {remove("probe_only");remove("outbound_tag")}
        val checked=requireNotNull(fromJson(clean.toString(),clean).carrier)
        return JSONObject().apply {
            fields.filter {it !in setOf("type","name","allow_insecure")&&checked.has(it)}.forEach {put(it,checked.get(it))}
            put("socks","127.0.0.1:$port");put("socks_user",user);put("socks_pass",password)
        }
    }
}
