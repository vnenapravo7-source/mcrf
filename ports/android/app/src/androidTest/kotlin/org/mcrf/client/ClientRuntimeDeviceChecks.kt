package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import java.util.function.Consumer

/** Native schema checks only. Does not start VPN/listeners, request consent or touch user settings. */
object ClientRuntimeDeviceChecks {
    @JvmStatic fun run(context: Context, passed: Consumer<String>) {
        // Read-only fixture override, never change the user's selected engine. Native
        // validation belongs to the verified external module, not the base APK.
        val selected=object:ContextWrapper(context){
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {
                val original=context.getSharedPreferences(name,mode)
                if(name!="MainActivity")return original
                return object:android.content.SharedPreferences by original {
                    override fun getAll():MutableMap<String,*> = original.all.toMutableMap().apply {put(ClientPrimarySelection.KEY,"singbox")}
                    override fun getString(key:String?,default:String?):String?=if(key==ClientPrimarySelection.KEY)"singbox"else original.getString(key,default)
                }
            }
        }
        fun base()=ProfileConfig.build("""{"outbounds":[{"type":"trojan","tag":"proxy","server":"fixture.invalid","server_port":443,"password":"fixture-only","tls":{"enabled":true,"server_name":"fixture.invalid"}}],"route":{"final":"proxy"}}""",true,10808)
        fun verify(label:String,tuning:CoreTuningSettings=CoreTuningSettings(),tun:ClientTunSettings=ClientTunSettings(),authenticated:Boolean=true) {
            val auth=LocalProxyConfig.generate(authenticated)
            val routed=RouteRules.apply(base(),RouteRules.defaults(),true)
            val tuned=CoreTuningConfig.apply(routed,tuning)
            val result=ClientTunConfig.apply(tuned,tun,auth,10808)
            ClientPrimarySelection.validateNative(selected,result.config.toString(),true);passed.accept(label)
        }
        verify("Android native core accepts default routing + core + authenticated TUN/mixed settings")
        verify("Android native core accepts custom TUN CIDRs, MTU and private bypass",tun=ClientTunSettings(mtu=1500,address4="172.25.0.1/30",address6="fdab:1234::1/126",bypassPrivate=true,bypassDirectIps=true))
        verify("Android native core accepts IPv4-only TUN",tun=ClientTunSettings(ipv6=false))
        verify("Android native core accepts authenticated LAN listener + DNS listener",tun=ClientTunSettings(shareLan=true,dnsPort=5533))
        verify("Android native core accepts Android HTTP proxy handoff without auth",tun=ClientTunSettings(httpProxy=true,httpBypass="*.example.com"),authenticated=false)
        verify("Android native core accepts TLS fragmentation, uTLS and smux defaults",tuning=CoreTuningSettings(fragmentDefault=true,fingerprint="chrome",muxDefault=true,muxPadding=true))
        val malformed=ClientTunSettings(mixedPort=10808,shareLan=true,lanPort=10808)
        check(runCatching {ClientTunConfig.apply(base(),malformed,LocalProxyConfig.generate(true),10808)}.isFailure)
        passed.accept("Conflicting TUN/proxy listener ports are rejected before native startup")
        // Independent runner modes test Clash/sing-box API, Mozilla CA and NTP.
        check(context.packageName=="org.mcrf.client")
    }
}
