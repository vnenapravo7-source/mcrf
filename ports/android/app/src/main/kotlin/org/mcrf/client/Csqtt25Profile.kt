package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Separate generation: never send these options through the legacy SWG normalizer. */
object Csqtt25Profile {
    const val GENERATION="2.5"
    const val ENGINE="csqtt25"
    fun isProfile(raw:JSONObject)=raw.optString("type")=="csqtt"&&raw.opt("csqtt_generation")==GENERATION
    private fun decode(value:String)=URLDecoder.decode(value.replace("+","%2B"),"UTF-8")
    private fun clean(value:String,max:Int,empty:Boolean=false):String=value.also {
        require((empty||it.isNotEmpty())&&it.toByteArray(Charsets.UTF_8).size<=max&&it.none {c->c.isISOControl()||c=='|'})
    }
    private fun endpoint(host:String,port:Int):String {
        require(host.isNotEmpty()&&host.length<=253&&port in 1..65535)
        val address="${if(':' in host)"[$host]"else host}:$port"
        val uri=URI("udp://$address")
        require(uri.host!=null&&uri.rawUserInfo==null&&uri.rawPath.isNullOrEmpty()&&uri.rawQuery==null&&uri.rawFragment==null)
        return address
    }
    private fun hash(value:String):String {
        val text=if(value.startsWith("https://",true)) {
            val uri=URI(value)
            require(uri.scheme=="https"&&uri.host in setOf("vk.com","vk.ru","m.vk.com","m.vk.ru")&&uri.userInfo==null&&uri.port==-1&&uri.path.startsWith("/call/join/"))
            decode(uri.rawPath.substringAfter("/call/join/").trimEnd('/'))
        }else value
        require(text.matches(Regex("[A-Za-z0-9_+/-]{16,4094}={0,2}")))
        return text
    }
    fun encryptedLink(source:String):ServerImport.Server {
        try {
        val uri=URI(Csqtt25Envelope.decode(source).link)
        val fields=linkedMapOf<String,String>()
        uri.rawQuery.split('&').forEach {part->
            val pair=part.split('=',limit=2);require(pair.size==2)
            val key=decode(pair[0]);require(key !in fields);fields[key]=pair[1]
        }
        require(fields.keys.all {it in setOf("v","host","peer","password","hashes","token","workers","name")})
        require(decode(fields.getValue("v"))=="2")
        val node=JSONObject().put("type","csqtt").put("csqtt_generation",GENERATION)
            .put("server",decode(fields.getValue("host"))).put("server_port",decode(fields.getValue("peer")).toInt())
            .put("password",decode(fields.getValue("password"))).put("name",fields["name"]?.let(::decode)?:"CSQTT 2.5")
            .put("link_workers",fields["workers"]?.let {decode(it).toInt()}?:0)
        fields["hashes"]?.let {raw->node.put("hashes",JSONArray(raw.split('+').map(::decode)))}
        fields["token"]?.let {node.put("vk_token",decode(it)).put("vk_hash_mode","auto_js")}
        return fromJson(source,node)
        }catch(_:Exception){throw IllegalArgumentException("Некорректная ссылка CSQTT 2.5. Проверь формат, потоки и источник звонков; старый движок не будет использован.")}
    }
    fun fromJson(source:String,input:JSONObject):ServerImport.Server {
        try {
            require(source.toByteArray(Charsets.UTF_8).size<=65536&&input.toString().toByteArray(Charsets.UTF_8).size<=65536&&isProfile(input))
            val allowed=setOf("type","csqtt_generation","name","server","server_port","password","hashes","vk_token","vk_hash_mode",
                "workers","link_workers","allow_hash_redistribution","captcha_mode","vk_auth_mode","obfs","fingerprint","turn_transport",
                "custom_turn_ips","client_ids","generation","salt","device_id","outbound_tag","probe_only")
            require(input.keys().asSequence().all {it in allowed})
            val host=clean(input.getString("server"),253);val port=input.get("server_port").toString().toInt();endpoint(host,port)
            val password=clean(input.getString("password"),1024)
            val name=clean(input.optString("name","CSQTT 2.5").ifBlank {"CSQTT 2.5"},256)
            val calls=when(val values=input.opt("hashes")) {
                is JSONArray->(0 until values.length()).map {hash(values.getString(it))}
                null,JSONObject.NULL->emptyList()
                else->values.toString().split(Regex("[,;\\s]+")).filter(String::isNotEmpty).map(::hash)
            }
            require(calls.size<=6&&calls.distinct().size==calls.size)
            val token=clean(input.optString("vk_token"),4096,true)
            val hashMode=input.optString("vk_hash_mode",if(token.isNotEmpty())"auto_js"else"manual")
            require(if(hashMode=="manual")calls.isNotEmpty()&&token.isEmpty() else hashMode=="auto_js"&&calls.isEmpty()&&token.isNotEmpty())
            fun number(key:String,fallback:Int)=if(input.has(key))input.get(key).toString().toInt()else fallback
            val fixed=number("link_workers",0);require(fixed==0||fixed in 3..99)
            val workers=if(fixed>0)fixed else number("workers",18)
            require(workers in 3..99&&(fixed>0||workers%9==0))
            fun flag(key:String):Boolean {val value=input.opt(key);require(value==null||value is Boolean);return value==true}
            val redistribute=flag("allow_hash_redistribution")
            require(fixed>0||redistribute||hashMode=="auto_js"||workers<=calls.size*27)
            fun option(key:String,fallback:String,values:Set<String>)=input.optString(key,fallback).also {require(it in values)}
            val mode=option("captcha_mode","auto",setOf("auto","wv","rjs"))
            val auth=option("vk_auth_mode",if(hashMode=="auto_js")"auto_js"else"vkcalls",setOf("vkcalls","legacy","auto_js"))
            require(auth!="auto_js"||hashMode=="auto_js")
            val ips=input.optJSONArray("custom_turn_ips")?.let {a->(0 until a.length()).map {a.getString(it)}}?:emptyList()
            require(ips.size<=256&&ips.distinct().size==ips.size)
            ips.forEach {ip->val literal=RouteRules.literal(ip);require(literal!=null&&!literal.isAnyLocalAddress&&!literal.isLoopbackAddress&&!literal.isMulticastAddress)}
            val node=JSONObject().put("type","csqtt").put("csqtt_generation",GENERATION).put("name",name).put("server",host).put("server_port",port)
                .put("password",password).put("hashes",JSONArray(calls)).put("vk_hash_mode",hashMode).put("workers",workers).put("link_workers",fixed)
                .put("allow_hash_redistribution",redistribute).put("captcha_mode",mode).put("vk_auth_mode",auth)
                .put("obfs",option("obfs","unified",setOf("unified","audio","video"))).put("fingerprint",clean(input.optString("fingerprint","chrome"),64,true))
                .put("turn_transport",option("turn_transport","udp",setOf("udp","tcp"))).put("custom_turn_ips",JSONArray(ips))
            if(token.isNotEmpty())node.put("vk_token",token)
            for(key in listOf("client_ids","salt","device_id"))input.optString(key).takeIf(String::isNotEmpty)?.let {node.put(key,clean(it,if(key=="client_ids")4096 else if(key=="salt")1024 else 256))}
            if(input.has("generation")){val generation=input.get("generation").toString().toInt();require(generation>=0);node.put("generation",generation)}
            return ServerImport.Server(source,name,"csqtt",host,port,null,null,null,node)
        }catch(_:Exception){throw IllegalArgumentException("Некорректный профиль CSQTT 2.5. Проверь версию, потоки и источник звонков; старый движок не будет использован.")}
    }
    fun privateConfig(input:JSONObject,udpPort:Int,socksPort:Int,user:String,password:String,installation:String):JSONObject {
        val p=fromJson("private-csqtt25",input).carrier!!
        require(udpPort in 1..65535&&socksPort in 1..65535)
        clean(user,255);clean(password,255);clean(installation,256)
        return JSONObject(p.toString()).apply {
            listOf("type","name","server","server_port","link_workers").forEach(::remove)
            put("peer",endpoint(p.getString("server"),p.getInt("server_port"))).put("listen","127.0.0.1:$udpPort")
            put("socks","127.0.0.1:$socksPort").put("socks_user",user).put("socks_pass",password)
            put("device_id",p.optString("device_id").ifEmpty {installation}).put("exact_workers",p.getInt("link_workers")>0)
        }
    }
}
