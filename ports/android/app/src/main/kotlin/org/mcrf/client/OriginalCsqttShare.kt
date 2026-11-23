package org.mcrf.client

import org.json.JSONObject
import java.net.URLEncoder

/** Preserve a real imported URI verbatim; JSON imports get the native v2 URI format. */
internal object OriginalCsqttShare {
    fun source(entry:ServerImport.Server):String {
        if(entry.original.trimStart().startsWith("csqtt://",true))return entry.original
        val original=runCatching {JSONObject(entry.original)}.getOrNull()
            ?.takeIf {it.optString("type")=="csqtt"}?:requireNotNull(entry.carrier)
        fun encode(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
        val fields=linkedMapOf("v" to "2","host" to original.getString("server"),"peer" to original.getInt("server_port").toString(),"password" to original.getString("password"))
        val values=fields.map {(key,value)->"$key=${encode(value)}"}.toMutableList()
        val calls=original.getJSONArray("hashes")
        if(calls.length()>0)values.add("hashes="+(0 until calls.length()).joinToString("+"){encode(calls.getString(it))})
        else if(original.optString("vk_token").isNotEmpty())values.add("token="+encode(original.getString("vk_token")))
        else return entry.original // Account-owned automatic calls have no portable provider credentials.
        original.keys().asSequence().filter {it !in setOf("type","server","server_port","password","hashes","vk_token",CarrierStreamDefaults.OVERRIDE)}.forEach {key->
            values.add("${encode(key)}=${encode(original.get(key).toString())}")
        }
        return "csqtt://connect?"+values.joinToString("&")
    }
}
