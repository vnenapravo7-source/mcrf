package org.mcrf.engines.singbox

import org.json.JSONObject

/** Complete final native configuration, never a reduced/stripped settings snapshot. */
class SingBoxRuntimeConfig private constructor(val mode: String,val metered: Boolean,private val json: String) {
    fun nativeJson(): String = json
    fun preflightTunPlan(): String = JSONObject().put("schema",1).put("mode",if(mode=="vpn")"deferred-native-options" else "no-tun")
        .put("metered",metered).toString()
    companion object {
        fun parse(envelope: String): SingBoxRuntimeConfig {
            require(envelope.toByteArray(Charsets.UTF_8).size<=4*1024*1024) { "CONFIG_SIZE" }
            val value=JSONObject(envelope)
            require(value.keys().asSequence().toSet()==setOf("schema","mode","metered","config")) { "CONFIG_FIELDS" }
            require(value.get("schema") is Int && value.getInt("schema")==1 && value.get("metered") is Boolean) { "CONFIG_TYPES" }
            require(value.get("mode") is String && value.getString("mode") in setOf("vpn","proxy","probe")) { "CONFIG_MODE" }
            val config=value.getJSONObject("config")
            val inbounds=config.optJSONArray("inbounds")
            val tunCount=(0 until (inbounds?.length() ?: 0)).count { inbounds!!.getJSONObject(it).optString("type")=="tun" }
            val mode=value.getString("mode")
            require(if(mode=="vpn")tunCount==1 else tunCount==0) { "CONFIG_TUN_MODE" }
            if(mode=="proxy")require(inbounds!=null && inbounds.length()>0) { "CONFIG_PROXY_INGRESS_REQUIRED" }
            if(mode=="probe") {
                require(inbounds==null || inbounds.length()==0) { "CONFIG_PROBE_INGRESS_FORBIDDEN" }
                require(config.optJSONArray("services").let {it==null || it.length()==0} && !config.has("ntp")) { "CONFIG_PROBE_SERVICE_FORBIDDEN" }
                require(config.optJSONObject("experimental").let {it==null || !it.has("clash_api") && !it.has("v2ray_api") && !it.has("debug")}) { "CONFIG_PROBE_API_FORBIDDEN" }
            }
            // Native checkConfig must validate every native field, including unsupported imports.
            return SingBoxRuntimeConfig(mode,value.getBoolean("metered"),config.toString())
        }
    }
}
