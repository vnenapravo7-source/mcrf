package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Isolated schema checks; coordinator can also feed these fixtures to Android Libbox.checkConfig. */
object CoreTuningNativeFixtures {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single()); root.mkdirs()
        val auth = JSONObject().put("enabled",true).put("username","fixture").put("password","fixture-only-password")
        fun base(type: String = "vless"): JSONObject {
            val out = JSONObject().put("type",type).put("tag","proxy").put("server","127.0.0.1").put("server_port",443).put("tls",JSONObject().put("enabled",true).put("server_name","example.org"))
            if (type in setOf("vless","vmess","tuic")) out.put("uuid","00000000-0000-4000-8000-000000000001")
            if (type in setOf("tuic","hysteria2","trojan")) out.put("password","fixture")
            return JSONObject().put("outbounds",JSONArray().put(out)).put("route",JSONObject().put("final","proxy"))
                .put("inbounds",JSONArray().put(JSONObject().put("type","tun").put("tag","tun-in").put("mtu",1400).put("address",JSONArray().put(ClientTunSettings.DEFAULT_ADDRESS4).put(ClientTunSettings.DEFAULT_ADDRESS6)).put("auto_route",true).put("stack","gvisor").put("dns_mode","hijack"))
                    .put(JSONObject().put("type","mixed").put("tag","local-proxy").put("listen","127.0.0.1").put("listen_port",10808)))
        }
        fun emit(name: String,c: JSONObject) { File(root,"CoreTuning-$name.json").writeText(c.toString(2)) }
        emit("tun-lan-dns",ClientTunConfig.apply(base(),ClientTunSettings(bypassPrivate=true,shareLan=true,dnsPort=1053),auth,10808).config)
        emit("http-handoff",ClientTunConfig.apply(base(),ClientTunSettings(httpProxy=true,httpBypass="*.example.org"),JSONObject(auth.toString()).put("enabled",false),10808).config)
        emit("core-services",CoreTuningConfig.apply(base(),CoreTuningSettings(certificateStore="mozilla",ntpEnabled=true,clashEnabled=true,clashSecret="fixture-secret-16",apiEnabled=true,apiSecret="fixture-secret-16")))
        emit("tls-mux",CoreTuningConfig.apply(base(),CoreTuningSettings(muxDefault=true,muxPadding=true,fragmentDefault=true,fingerprint="chrome")))
        val quic = CoreTuningSettings(idleTimeout="30s",keepAlive="10s",streamWindow="1048576",connectionWindow="4194304",maxStreams=100,initialPacketSize=1300,disablePmtu=true)
        emit("hysteria2",CoreTuningConfig.apply(base("hysteria2"),quic))
        emit("tuic",CoreTuningConfig.apply(base("tuic"),quic))
        val http = base().put("http_clients",JSONArray().put(JSONObject().put("tag","h2-default").put("version",2)))
        emit("http-client",CoreTuningConfig.apply(http,quic))
        emit("unsupported-custom-fragment",base().also { it.getJSONArray("outbounds").getJSONObject(0).put("fragment_size","10-100") })
        emit("unsupported-tls-tricks",base().also { it.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").put("tls_tricks",JSONObject().put("mixedcase_sni",true)) })
    }
}
