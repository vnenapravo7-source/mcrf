package org.mcrf.engines.singbox
import org.json.JSONArray
import org.json.JSONObject

object SingBoxRuntimeConfigChecks {
    @JvmStatic fun main(args: Array<String>) {
        var checks=0
        fun checkThat(value: Boolean) {check(value);checks++}
        fun rejects(run: ()->Unit) {try {run()}catch(_:IllegalArgumentException){checks++;return};error("Expected rejection")}
        fun envelope(mode: String,type: String)=JSONObject().put("schema",1).put("mode",mode).put("metered",false)
            .put("config",JSONObject().put("inbounds",JSONArray().put(JSONObject().put("type",type))))
        val native=envelope("vpn","tun")
        native.getJSONObject("config").put("unknown-native-option",JSONObject().put("preserve",true))
        val parsed=SingBoxRuntimeConfig.parse(native.toString())
        checkThat(JSONObject(parsed.nativeJson()).getJSONObject("unknown-native-option").getBoolean("preserve"))
        native.getJSONObject("config").remove("unknown-native-option")
        checkThat(JSONObject(parsed.nativeJson()).has("unknown-native-option"))
        checkThat(JSONObject(parsed.preflightTunPlan()).getString("mode")=="deferred-native-options")
        checkThat(JSONObject(SingBoxRuntimeConfig.parse(envelope("proxy","mixed").toString()).preflightTunPlan()).getString("mode")=="no-tun")
        rejects {SingBoxRuntimeConfig.parse(envelope("proxy","tun").toString())}
        rejects {SingBoxRuntimeConfig.parse(envelope("vpn","mixed").toString())}
        rejects {SingBoxRuntimeConfig.parse(envelope("proxy","mixed").put("silentFallback",true).toString())}
        rejects {SingBoxRuntimeConfig.parse(envelope("proxy","mixed").put("schema","1").toString())}
        rejects {SingBoxRuntimeConfig.parse(envelope("proxy","mixed").put("metered","false").toString())}
        rejects {SingBoxRuntimeConfig.parse(envelope("proxy","mixed").put("mode","automatic").toString())}
        val empty=envelope("proxy","mixed");empty.getJSONObject("config").put("inbounds",JSONArray())
        rejects {SingBoxRuntimeConfig.parse(empty.toString())}
        val duplicate=envelope("vpn","tun");duplicate.getJSONObject("config").getJSONArray("inbounds").put(JSONObject().put("type","tun"))
        rejects {SingBoxRuntimeConfig.parse(duplicate.toString())}
        val probe=envelope("probe","mixed");probe.getJSONObject("config").put("inbounds",JSONArray())
        checkThat(SingBoxRuntimeConfig.parse(probe.toString()).mode=="probe")
        rejects {SingBoxRuntimeConfig.parse(envelope("probe","mixed").toString())}
        val admin=JSONObject(probe.toString());admin.getJSONObject("config").put("experimental",JSONObject().put("clash_api",JSONObject()))
        rejects {SingBoxRuntimeConfig.parse(admin.toString())}
        val ntp=JSONObject(probe.toString());ntp.getJSONObject("config").put("ntp",JSONObject())
        rejects {SingBoxRuntimeConfig.parse(ntp.toString())}
        println("PASS $checks complete-config envelope checks; no native/Android execution")
    }
}
