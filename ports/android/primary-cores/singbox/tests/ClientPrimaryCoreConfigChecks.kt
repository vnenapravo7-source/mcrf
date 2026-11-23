package org.mcrf.client
import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Engine
import java.lang.reflect.Proxy
import java.security.MessageDigest
object ClientPrimaryCoreConfigChecks {
    @JvmStatic fun main(args:Array<String>) {
        var checks=0;var accepted=true;var wrongProof=false;var calls=0
        val caps="""{"schema":1,"kind":"sing-box","spi":1,"coreApi":1,"fullNativeConfig":true,"tunPlanMode":"deferred-native-options","oneRunPerProcess":true,"proxyOnly":true,"isolatedProbe":true}"""
        val sha=MessageDigest.getInstance("SHA-256").digest(caps.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
        val engine=Proxy.newProxyInstance(Engine::class.java.classLoader,arrayOf(Engine::class.java)) { _,method,values ->
            when(method.name) {"capabilitiesJson"->caps;"validate"->{calls++;if(!accepted)Engine.Validation(false,"fixture","config")
                else if(values!![2]==Engine.Mode.PROXY)Engine.Validation.proxyValidated(values[0] as String,values[1] as String)
                else Engine.Validation.deferredVpnValidated(values[0] as String,if(wrongProof)"changed" else values[1] as String,sha)}
                else->error("No runtime callback allowed ${method.name}")}
        } as Engine
        fun config(type:String)=JSONObject().put("inbounds",JSONArray().put(JSONObject().put("type",type))).put("preserved-native-field","fixture-secret")
        fun verify(value:Boolean){check(value);checks++}
        fun rejects(run:()->Unit){try{run()}catch(_:IllegalArgumentException){checks++;return};error("Expected rejection")}
        val source=config("tun");val prepared=ClientPrimaryCoreConfig.prepareSingBox(engine,"profile",source,true,true)
        verify(prepared.mode==Engine.Mode.VPN && prepared.acquisition==Engine.TunAcquisition.DEFERRED)
        verify(prepared.request(7,"127.0.0.1","fixture",emptyArray()).borrowedTunFd == -1)
        source.remove("preserved-native-field");verify(JSONObject(prepared.envelope).getJSONObject("config").getString("preserved-native-field")=="fixture-secret")
        verify("fixture-secret" !in prepared.toString() && "profile" !in prepared.toString())
        val proxy=ClientPrimaryCoreConfig.prepareSingBox(engine,"profile",config("mixed"),false,false)
        verify(proxy.mode==Engine.Mode.PROXY && proxy.acquisition==Engine.TunAcquisition.NONE)
        verify(proxy.request(8,"127.0.0.1","fixture",emptyArray()).borrowedTunFd == -1)
        val before=calls;rejects {ClientPrimaryCoreConfig.prepareSingBox(engine,"profile",config("tun"),false,false)};verify(calls==before)
        accepted=false;rejects {ClientPrimaryCoreConfig.prepareSingBox(engine,"profile",config("tun"),true,false)}
        accepted=true;wrongProof=true;rejects {ClientPrimaryCoreConfig.prepareSingBox(engine,"profile",config("tun"),true,false)}
        wrongProof=false;val probeConfig=JSONObject().put("inbounds",JSONArray()).put("outbounds",JSONArray())
        val probe=ClientPrimaryCoreConfig.prepareSingBoxProbe(engine,"profile",probeConfig)
        verify(JSONObject(probe.envelope).getString("mode")=="probe" && probe.acquisition==Engine.TunAcquisition.NONE)
        verify(probe.request(9,"","fixture",emptyArray()).mode==Engine.Mode.PROXY)
        rejects {ClientPrimaryCoreConfig.prepareSingBoxProbe(engine,"profile",config("mixed"))}
        println("PASS $checks real host-envelope/constructor wiring checks with declared preflight fixture; no native checkConfig or runtime success")
    }
}
