package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Helpers for the existing isolated ProbeService; coordinator owns its exclusive lease/IPC. */
object WarpProbePlan {
    const val OPERATION = "warp_trace"
    const val URL = "https://www.cloudflare.com/cdn-cgi/trace"
    fun request(candidate: String, port: Int, user: String, password: String): JSONObject {
        val entry=ServerImport.read(WireGuardProfile.parse(candidate).conf()).single()
        return JSONObject().put("operation",OPERATION).put("config",configuration(candidate,port,user,password))
            .put("servers",JSONArray().put(JSONObject().put("id",entry.id).put("tag",ServerImport.tag(entry))))
            .put("tracePort",port).put("traceUser",user).put("tracePassword",password)
    }
    fun configuration(candidate: String, port: Int, user: String, password: String): JSONObject {
        require(port in 1024..65535)
        require(user.length in 1..64 && password.length in 16..128 && (user+password).all { it.code in 33..126 })
        val entry = ServerImport.read(WireGuardProfile.parse(candidate).conf()).single()
        require(WarpProfile.isWarp(entry)) { "Проверяется только конкретный профиль WARP." }
        val generated = ServerImport.build(listOf(entry),entry.id,false,60,50,"Cloudflare")
        return ProfileConfig.build(generated.toString(),false,port).apply {
            put("inbounds",JSONArray().put(JSONObject().put("type","mixed").put("tag","warp-trace-local")
                .put("listen","127.0.0.1").put("listen_port",port)
                .put("users",JSONArray().put(JSONObject().put("username",user).put("password",password)))))
            getJSONObject("route").put("final",ServerImport.tag(entry))
        }
    }
}
