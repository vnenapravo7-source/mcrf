package org.mcrf.client

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import java.io.File
import java.util.function.Consumer
import org.json.JSONObject

/** Private runtime input, explicit test-device enrollment authority; never imports into catalogue. */
object RelayIncidentDeviceChecks {
    @JvmStatic fun run(test:Instrumentation,filename:String,passed:Consumer<String>) {
        require(BuildConfig.DEBUG && filename.matches(Regex("mcrf-relay-input-[0-9a-f]{32}\\.json")))
        val context=test.targetContext
        val file=File(context.filesDir,filename)
        require(file.isFile && file.length() in 2..65536)
        val before=ProfileStore.snapshot(context)
        val preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
        fun report(value:String)=test.sendStatus(0,Bundle().apply {putString("stream","RELAY_INCIDENT $value\n")})
        fun code(error:Exception):String {
            val message=error.message.orEmpty()
            return if(message.matches(Regex("PRIMARY_[A-Z0-9_]{1,96}")))message else error.javaClass.simpleName
        }
        try {
            val input=JSONObject(file.readText())
            require(input.getBoolean("authorizeTestDeviceEnrollment"))
            val links=input.getJSONArray("links")
            require(links.length()==6)
            val entries=(0 until links.length()).map {index->
                val entry=ServerImport.read(links.getString(index)).single()
                require(entry.protocol in setOf("wdtt","csqtt") && entry.host in setOf("13.140.9.158","94.159.103.27","188.253.24.174"))
                require(entry.carrier!!.getJSONArray("hashes").length()==6)
                report("case=${index+1} protocol=${entry.protocol} import=ok calls=6")
                entry
            }.toMutableList()
            val previous=Connections(context).apply {load()}.entries().firstOrNull {it.protocol=="qwdtt" && it.host=="94.159.103.27"}
            if(previous!=null)entries.add(previous) else report("protocol=qwdtt outcome=previous_profile_not_found")
            entries.forEachIndexed {index,entry->
                check(!Thread.currentThread().isInterrupted)
                val started=android.os.SystemClock.elapsedRealtime()
                var phase="module"
                try {
                    NativeCarrier.executable(context,entry.protocol)
                    phase="vpn_preflight"
                    val config=ServerImport.build(listOf(entry),entry.id,false,60,50,"Cloudflare")
                    val routes=RoutingStore(context).apply {load()}.snapshot()
                    DnsConfig.applyGenerated(config,routes.getJSONObject("globalDns"))
                    val tun=ClientTunStore(context)
                    val prepared=LocalProxyConfig.apply(ProfileConfig.build(config.toString(),true,10808),tun.auth())
                    val routed=RouteRules.apply(prepared,routes,true,PresetAssets.resolve(context,routes))
                    ClientPrimarySelection.validateNative(context,routed.toString(),true)
                    report("case=${index+1} protocol=${entry.protocol} vpn_preflight=ok")
                    phase="https_probe"
                    val values=ProbeCoordinator.measure(context,listOf(entry),"https://www.gstatic.com/generate_204",ProbeCoordinator.cancellationToken())
                    val verified=values[entry.id]?.let {it>=0}==true
                    report("case=${index+1} protocol=${entry.protocol} outcome=${if(verified)"https_verified"else"https_not_verified"} wall_ms=${android.os.SystemClock.elapsedRealtime()-started}")
                }catch(error:Exception){
                    if(Thread.currentThread().isInterrupted)throw error
                    report("case=${index+1} protocol=${entry.protocol} phase=$phase error=${code(error)} wall_ms=${android.os.SystemClock.elapsedRealtime()-started}")
                }finally{ProbeCoordinator.awaitQuiescent(context)}
                check(ProfileStore.snapshot(context)==before && context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences)
            }
        }finally {
            check(file.delete() || !file.exists())
            check(ProfileStore.snapshot(context)==before && context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences)
        }
        passed.accept("Relay incident diagnostics completed with unchanged user state; individual outcomes, not blanket traffic PASS")
    }
}
