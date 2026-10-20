package org.mcrf.client

import org.json.JSONObject
import java.io.File

/** Fake credentials and localhost endpoints only; native --check never starts a listener. */
object OptionalCoreTuningFixtures {
    @JvmStatic fun main(args: Array<String>) {
        val directory=File(args.single()).apply {mkdirs()}
        val source=JSONObject("""{"type":"vless","tag":"proxy","server":"127.0.0.1","server_port":443,"uuid":"4b72af9e-32b4-4b14-a2fc-4a9100d2eb21","tls":{"enabled":true,"server_name":"fixture.example.test"}}""")
        listOf(OptionalCoreSettings(xrayVlessDefault=true),OptionalCoreSettings(customFragmentDefault=true,mixedCaseSniDefault=true)).forEach {settings ->
            val prepared=OptionalCoreTuning.prepare(source,settings,CoreTuningSettings(fingerprint="firefox",insecure=!settings.xrayVlessDefault,muxDefault=!settings.xrayVlessDefault,certificateStore=if(settings.xrayVlessDefault) "system" else "mozilla"))
            val request=requireNotNull(prepared.request)
            val config=OptionalCoreConfig.privateConfig(request,10900,"fixture-user-123456","fixture-password-123456")
            File(directory,"${request.kind}.json").writeText(config.toString())
        }
        listOf("ws","grpc","httpupgrade").forEach {transport ->
            val outbound=JSONObject(source.toString())
            val config=JSONObject().put("type",transport)
            if(transport=="grpc")config.put("service_name","mcrf-fixture")
            else config.put("path","/mcrf-fixture").put("headers",JSONObject().put("X-MCRF-Fixture","owned-loopback"))
            if(transport=="httpupgrade")config.put("host","fixture.example.test")
            outbound.put("transport",config)
            val request=requireNotNull(OptionalCoreConfig.prepare(outbound,OptionalCoreSettings(xrayVlessDefault=true)))
            File(directory,"xray-$transport.json").writeText(OptionalCoreConfig.privateConfig(request,10900,"fixture-user-123456","fixture-password-123456").toString())
        }
        val nativeOut=XrayConfig.fromSingBox(source)
        nativeOut.getJSONObject("streamSettings").remove("tlsSettings")
        val native=JSONObject().put("type","xray").put("outbound",nativeOut)
        val request=requireNotNull(OptionalCoreTuning.prepare(native,OptionalCoreSettings(),CoreTuningSettings(fingerprint="firefox")).request)
        File(directory,"xray-native-tls-default.json").writeText(OptionalCoreConfig.privateConfig(request,10900,"fixture-user-123456","fixture-password-123456").toString())
    }
}
