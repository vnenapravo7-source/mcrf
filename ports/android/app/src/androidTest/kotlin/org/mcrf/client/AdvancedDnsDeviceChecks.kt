package org.mcrf.client

import android.content.Context
import android.content.ContextWrapper
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Coordinator runs through its instrumented runner. No VPN start, account calls or catalogue mutations. */
object AdvancedDnsDeviceChecks {
    @JvmStatic fun run(context: Context): String {
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
        fun native(config:JSONObject)=ClientPrimarySelection.validateNative(selected,config.toString(),true)
        fun base()=ProfileConfig.build("""{"outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1234}],"route":{"final":"proxy"}}""",true,10808)
        fun verify(s:JSONObject) { native(RouteRules.apply(base(),s,true)) }
        val state=RouteRules.defaults().put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",true))
        verify(state)
        state.getJSONObject("advancedDns").put("remoteNoAAAA",true).put("directNoAAAA",true)
        state.getJSONArray("rules").put(JSONObject().put("id","native-fixture").put("name","fixture").put("text","example.com").put("mode","addresses").put("target","direct"))
        verify(state)
        state.getJSONObject("advancedDns").put("fakeIp",true).put("fakeNoAAAA",false).put("optimistic",true).put("answers","192.0.2.1 fixture.example\n2001:db8::1 fixture.example")
        verify(state)
        state.getJSONObject("advancedDns").put("hosts",true)
        verify(state)
        val temporary=mutableListOf<File>()
        try {
            fun fixture(kind:String,bytes:ByteArray):String {
                val file=File.createTempFile("mcrf-dns-native-",".json",context.cacheDir)
                temporary.add(file)
                file.writeText(GeoDataConfig.convert(bytes,kind,"fixture").toString())
                return file.absolutePath
            }
            val domain=integer(1,2)+blob(2,"fixture.example".toByteArray())
            val site=fixture("geosite",blob(1,blob(1,"fixture".toByteArray())+blob(2,domain)))
            val ipv4=blob(1,byteArrayOf(192.toByte(),0,2,0))+integer(2,24)
            val ipv6=blob(1,byteArrayOf(0x20,0x01,0x0d,0xb8.toByte(),0,0,0,0,0,0,0,0,0,0,0,0))+integer(2,32)
            val ip=fixture("geoip",blob(1,blob(1,"fixture".toByteArray())+blob(2,ipv4)+blob(2,ipv6)))
            val provider=GeoDataConfig.fromProvider(JSONObject("""{"RouteOrder":"direct-proxy-block","DirectSites":["full:local.fixture.example","geosite:fixture","keyword:bank"],"ProxySites":["regexp:.*fixture[.]example$"],"BlockIp":["geoip:fixture"]}"""))
            val combined=RouteRules.defaults().put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",true)).put("geoData",provider)
            native(RouteRules.apply(base(),combined,true,mapOf("geo-0" to site,"geo-1" to ip)))
            val inverse=fixture("geoip",blob(1,blob(1,"fixture".toByteArray())+blob(2,ipv4)+integer(3,1)))
            native(RouteRules.apply(base(),combined,true,mapOf("geo-0" to site,"geo-1" to inverse)))
            val native=RemoteRoutingImports.normalize(JSONObject("""{"kind":"throne-route-profile","rules":[{"type":"custom","domain":["fixture.example"],"ip_cidr":["192.0.2.0/24"],"network":"tcp","port":443,"outbound":"direct"},{"type":"logical","mode":"and","invert":true,"rules":[{"domain_suffix":["fixture.example"]},{"package_name":["org.mcrf.client"]}],"outbound":"proxy"}]}"""))
            verify(RouteRules.defaults().put("advancedDns",AdvancedDnsConfig.defaults().put("enabled",true)).put("rules",native.getJSONArray("rules")))
            val profile=base().put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("type","udp").put("tag","profile-dns").put("server","192.0.2.53"))).put("final","profile-dns"))
            DnsConfig.markProfileDns(profile)
            DnsConfig.applyGenerated(profile,DnsConfig.defaults())
            check(!profile.has(DnsConfig.PROFILE_DNS_MARKER))
            native(profile)
        } finally { temporary.forEach {check(it.delete()||!it.exists()) {"Native DNS fixture cleanup failed"}} }
        return "DNS/provider/native routes: 8 config cases accepted; no traffic, dataset download or catalogue change performed"
    }
    private fun variable(value:Long):ByteArray {var n=value;val out=ByteArrayOutputStream();while(n>127){out.write((n.toInt() and 127) or 128);n=n ushr 7};out.write(n.toInt());return out.toByteArray()}
    private fun blob(field:Int,bytes:ByteArray)=variable((field*8+2).toLong())+variable(bytes.size.toLong())+bytes
    private fun integer(field:Int,value:Int)=variable((field*8).toLong())+variable(value.toLong())
}
