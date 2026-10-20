package org.mcrf.client

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** Installed native modules only; endpoint 127.0.0.1:9 is never dialled by these listener-only checks. */
object ClientOptionalDeviceChecks {
    @JvmStatic fun run(instrumentation: Instrumentation, passed: Consumer<String>) {
        val context=instrumentation.targetContext
        val before=context.cacheDir.listFiles().orEmpty().map {it.name}.toSet()
        for(kind in listOf("xray","tls-core")) {
            EngineModules.installation(context,kind)
            passed.accept("$kind installed APK has matching signature, kind, ingress, source commit and module API")
            val output=if(kind=="xray") JSONObject().put("protocol","vless")
                .put("settings",JSONObject().put("vnext",JSONArray().put(JSONObject().put("address","127.0.0.1").put("port",9)
                    .put("users",JSONArray().put(JSONObject().put("id","00000000-0000-4000-8000-000000000001").put("encryption","none"))))))
                .put("streamSettings",JSONObject().put("network","raw").put("security","none"))
            else JSONObject().put("type","http").put("tag","fixture").put("server","127.0.0.1").put("server_port",9)
                .put("tls",JSONObject().put("enabled",true).put("server_name","localhost"))
                .put("tls_fragment",JSONObject().put("enabled",true).put("size","20-40").put("sleep","0-1"))
            val request=OptionalCoreConfig.Request(kind,output)
            OptionalCoreProcess.check(context,request,BooleanSupplier {false})
            passed.accept("$kind actual Android binary accepts its supported fixture configuration")
            val child=OptionalCoreProcess.start(context,request,BooleanSupplier {false},false,false)
            val port=child.port
            try {
                check(child.isAlive()&&NativeCarrier.socksReady(port,child.username,child.password))
                check(!NativeCarrier.socksReady(port,child.username,"incorrect-fixture-password"))
                passed.accept("$kind native listener authenticates fresh credentials and rejects an incorrect password")
                val bridge=child.bridgeOutbound("fixture")
                check(bridge.getString("tag")=="fixture")
                check(if(kind=="tls-core")bridge.optString("network")=="tcp" else !bridge.has("network"))
            } finally {child.close()}
            check(!child.isAlive())
            val refused=try {Socket().use {it.connect(InetSocketAddress("127.0.0.1",port),500)};false}catch(_:Exception){true}
            check(refused) {"$kind listener still accepts connections after STOP"}
            passed.accept("$kind STOP closes the native process and its authenticated loopback listener")
            val cancelled=try {OptionalCoreProcess.check(context,request,BooleanSupplier {true});false}catch(_:IllegalArgumentException){true}
            check(cancelled)
            passed.accept("$kind cancellation stops native preflight without contacting an external server")
        }
        check(context.cacheDir.listFiles().orEmpty().none {it.name !in before&&it.name.startsWith("mcrf-optional-")})
        passed.accept("Optional module lifecycle removes only its own generated private fixture directories")
    }
}
