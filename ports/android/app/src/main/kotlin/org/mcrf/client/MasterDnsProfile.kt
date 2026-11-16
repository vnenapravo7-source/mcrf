package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Explicit MCRF JSON wrapper; does not pretend to implement an upstream URI. */
internal object MasterDnsProfile {
    const val KIND="masterdnsvpn"
    const val REPOSITORY="masterking32/MasterDnsVPN"
    const val COMMIT="acbf1c61f90786f41b975d2e2f616afbce292b29"
    private val fields=setOf("type","name","domains","resolvers","encryption_method","encryption_key","base_encode_data","upload_compression","download_compression","workers")
    fun compatible(kind:String?,repository:String?,commit:String?,contract:Int)=kind==KIND&&repository==REPOSITORY&&commit==COMMIT&&contract==1
    fun fromJson(source:String,raw:JSONObject):ServerImport.Server {
        require(source.toByteArray(Charsets.UTF_8).size<=65536&&raw.optString("type")==KIND&&raw.keys().asSequence().all {it in fields}&&fields.none {raw.has(it)&&raw.isNull(it)}) {"Неизвестное, пустое или слишком большое поле MasterDnsVPN."}
        val data=JSONObject(raw.toString())
        val name=data.optString("name","MasterDnsVPN")
        require(name.length in 1..256&&name.none(Char::isISOControl)) {"Название: 1–256 символов без управляющих знаков."}
        fun strings(key:String):List<String> {
            val values=data.getJSONArray(key)
            require(values.length()>0) {"Укажите $key для MasterDnsVPN."}
            return (0 until values.length()).map {index->val value=values.get(index);require(value is String);value}
        }
        val domains=strings("domains").map {it.lowercase(java.util.Locale.ROOT)}.distinct()
        require(domains.all {it.length<=253&&it.matches(Regex("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+"))}) {"MasterDnsVPN: делегированные DNS-домены без схемы и пути."}
        val resolvers=strings("resolvers").distinct()
        try {resolvers.forEach(CarrierProfile::numericResolver)}catch(_:Exception){throw IllegalArgumentException("MasterDnsVPN: каждый резолвер — IP:порт; IPv6 — [IP]:порт.")}
        val keyValue=data.get("encryption_key");require(keyValue is String)
        val key=keyValue
        require(key.toByteArray(Charsets.UTF_8).size in 1..4096&&key.none(Char::isISOControl)) {"Укажите ключ MasterDnsVPN из настроек сервера, до 4096 байт без управляющих знаков."}
        fun number(field:String,default:Int?=null):Int {
            if(!data.has(field)){require(default!=null) {"Укажите метод шифрования MasterDnsVPN явно."};return default}
            val value=data.get(field);require(value is Number&&value.toDouble()==value.toInt().toDouble()) {"$field должен быть целым числом."};return value.toInt()
        }
        val method=number("encryption_method");val workers=number("workers",2)
        val upload=number("upload_compression",0);val download=number("download_compression",0)
        require(method in 0..5&&workers in 1..8&&upload in 0..3&&download in 0..3) {"Проверьте метод шифрования, сжатие и число рабочих потоков MasterDnsVPN."}
        if(data.has("base_encode_data"))require(data.get("base_encode_data") is Boolean) {"base_encode_data — логическое значение."}
        data.put("name",name).put("domains",JSONArray(domains)).put("resolvers",JSONArray(resolvers)).put("encryption_method",method).put("workers",workers)
            .put("upload_compression",upload).put("download_compression",download).put("base_encode_data",data.optBoolean("base_encode_data",false))
        return ServerImport.Server(source,name,KIND,domains.first(),53,null,null,null,data)
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
