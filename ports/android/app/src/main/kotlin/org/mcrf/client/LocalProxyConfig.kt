package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/** Credentials for the public local mixed listener, not the private carrier SOCKS bridge. */
object LocalProxyConfig {
    fun generate(enabled:Boolean=true):JSONObject {
        val random=SecureRandom()
        fun token(count:Int)=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(count).also(random::nextBytes))
        return JSONObject().put("enabled",enabled).put("username","mcrf-${token(9)}").put("password",token(24))
    }
    fun validate(value:JSONObject):JSONObject {
        require(value.keys().asSequence().toSet()==setOf("enabled","username","password")){"Некорректные настройки авторизации прокси."}
        value.getBoolean("enabled")
        listOf("username","password").forEach {key->val text=value.getString(key);require(text.toByteArray(Charsets.UTF_8).size in 1..255&&text.none(Char::isISOControl)){"Некорректные данные авторизации прокси."}}
        return JSONObject(value.toString())
    }
    fun link(scheme:String,port:Int,auth:JSONObject):String {
        require(scheme in setOf("socks5","http")&&port in 1024..65535);validate(auth)
        fun encode(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
        val credentials=if(auth.getBoolean("enabled"))"${encode(auth.getString("username"))}:${encode(auth.getString("password"))}@"else""
        return "$scheme://${credentials}127.0.0.1:$port"
    }
    fun apply(config:JSONObject,auth:JSONObject):JSONObject {
        validate(auth);val listeners=config.getJSONArray("inbounds")
        repeat(listeners.length()){val item=listeners.getJSONObject(it);if(item.optString("tag")=="local-proxy"){
            require(item.optString("type")=="mixed"&&item.optString("listen")=="127.0.0.1")
            if(auth.getBoolean("enabled"))item.put("users",JSONArray().put(JSONObject().put("username",auth.getString("username")).put("password",auth.getString("password"))))else item.remove("users")
        }}
        return config
    }
}
