package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Fedarisha S3 transport only. Never reinterpret it as stock Xray/direct. */
object S3BypassProfile {
    const val KIND="s3bypass"
    const val REPOSITORY="Fedarisha/Xray-core-fedarisha"
    const val COMMIT="b54ed13e3d7473ad8c25b5c39dfe127b004db540"
    fun isProfile(raw:JSONObject):Boolean = raw.optString("type") in setOf(KIND,"s3-bypass")||
        raw.optJSONArray("outbounds")?.let {list->(0 until list.length()).any {list.optJSONObject(it)?.optString("protocol")=="fedarisha"}}==true
    private fun exactKeys(value:JSONObject,allowed:Set<String>){require(value.keys().asSequence().all {it in allowed}) {"S3 Bypass: неподдерживаемое поле профиля."}}
    fun fromJson(source:String,raw:JSONObject):ServerImport.Server {
        require(source.toByteArray(Charsets.UTF_8).size<=65536) {"Профиль S3 Bypass больше64КБ."}
        val settings=if(raw.optString("type") in setOf(KIND,"s3-bypass")){
            exactKeys(raw,setOf("type","name","settings","outbound_tag","probe_only"));raw.getJSONObject("settings")
        }else {
            exactKeys(raw,setOf("log","inbounds","outbounds","remarks","mcrf_name"))
            val out=raw.getJSONArray("outbounds")
            val proxies=(0 until out.length()).map {out.getJSONObject(it)}.filter {it.optString("protocol")=="fedarisha"}
            require(proxies.size==1&&(0 until out.length()).all {out.getJSONObject(it).optString("protocol") in setOf("fedarisha","freedom","blackhole")}) {"S3 Bypass: нужен один выход fedarisha без других прокси."}
            raw.optJSONArray("inbounds")?.let {inputs->repeat(inputs.length()){
                val input=inputs.getJSONObject(it)
                require(input.optString("protocol")=="socks"&&input.optString("listen","127.0.0.1") in setOf("127.0.0.1","::1")) {"S3 Bypass: разрешён только локальный SOCKS-вход."}
            }}
            exactKeys(proxies.single(),setOf("tag","protocol","settings"))
            proxies.single().getJSONObject("settings")
        }
        exactKeys(settings,setOf("storage","tuning","userLevel"))
        if(settings.has("userLevel")){
            val level=settings.get("userLevel") as? Number
            require(level!=null&&level.toDouble()==level.toLong().toDouble()&&level.toLong() in 0..65535) {"S3 Bypass: неподдерживаемый уровень пользователя."}
        }
        val storage=settings.getJSONObject("storage")
        exactKeys(storage,setOf("type","bucket","endpoint","region","prefix","sessionsDir","accessKey","secretKey"))
        require(storage.getString("type")=="s3") {"S3 Bypass: нужен тип хранилища s3."}
        val endpoint=URI(storage.getString("endpoint"))
        require(endpoint.scheme=="https"&&!endpoint.host.isNullOrBlank()&&endpoint.userInfo==null&&endpoint.rawQuery==null&&endpoint.rawFragment==null&&endpoint.port!=0&&endpoint.port<=65535&&endpoint.path.orEmpty() in setOf("","/")) {"S3 Bypass: укажи HTTPS endpoint без логина, параметров и пути."}
        require(storage.getString("bucket").matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))) {"S3 Bypass: проверь имя S3-корзины."}
        require(storage.getString("region").matches(Regex("[A-Za-z0-9._-]{1,128}"))) {"S3 Bypass: проверь регион."}
        for(key in listOf("accessKey","secretKey")){val value=storage.getString(key);require(value.length in 1..4096&&value.all {it.code in 33..126}) {"S3 Bypass: укажи корректные ключи доступа без пробелов."}}
        val prefix=storage.optString("prefix","fedarisha/")
        require(prefix.length<=1024&&prefix.none {it.isISOControl()||it=='\\'}&&prefix.split('/').none {it=="."||it==".."}) {"S3 Bypass: проверь префикс объектов."}
        val sessions=storage.optString("sessionsDir","sessions")
        require(sessions.matches(Regex("[A-Za-z0-9._-]{1,128}"))&&sessions !in setOf(".","..")) {"S3 Bypass: проверь каталог сессий."}
        val tuning=settings.optJSONObject("tuning")?:JSONObject()
        val limits=mapOf("pollIntervalMs" to 10L..60000L,"writeIntervalMs" to 1L..60000L,"idleTimeoutSec" to 30L..86400L,"maxFileSizeBytes" to 1024L..67108864L)
        exactKeys(tuning,limits.keys)
        tuning.keys().forEach {key->val value=tuning.get(key) as? Number;require(value!=null&&value.toDouble()==value.toLong().toDouble()&&(value.toLong()==0L||value.toLong() in limits.getValue(key))) {"S3 Bypass: неподдерживаемые параметры интервалов или размера объектов."}}
        val name=raw.optString("name",raw.optString("remarks",raw.optString("mcrf_name","S3 Bypass")))
        require(name.length in 1..256&&name.none(Char::isISOControl)) {"S3 Bypass: проверь название."}
        val normalized=JSONObject().put("storage",JSONObject(storage.toString()).put("prefix",prefix).put("sessionsDir",sessions)).put("tuning",JSONObject(tuning.toString()))
        if(settings.has("userLevel"))normalized.put("userLevel",settings.getLong("userLevel"))
        val profile=JSONObject().put("type",KIND).put("name",name).put("settings",normalized)
        if(raw.optBoolean("probe_only"))profile.put("probe_only",true)
        return ServerImport.Server(source,name,KIND,endpoint.host,if(endpoint.port>0)endpoint.port else 443,null,null,null,profile)
    }
    fun privateConfig(profile:JSONObject,port:Int,user:String,password:String):JSONObject {
        val checked=fromJson(profile.toString(),profile).carrier!!
        require(port in 1..65535&&user.isNotBlank()&&password.isNotBlank())
        return JSONObject().put("log",JSONObject().put("loglevel","warning"))
            .put("inbounds",JSONArray().put(JSONObject().put("tag","mcrf-s3-loopback").put("listen","127.0.0.1").put("port",port).put("protocol","socks")
                .put("settings",JSONObject().put("auth","password").put("udp",true).put("accounts",JSONArray().put(JSONObject().put("user",user).put("pass",password))))))
            .put("outbounds",JSONArray().put(JSONObject().put("tag","fedarisha-out").put("protocol","fedarisha").put("settings",checked.getJSONObject("settings"))))
    }
}
