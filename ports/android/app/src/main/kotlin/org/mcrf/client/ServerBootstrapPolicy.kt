package org.mcrf.client

import org.json.JSONObject
import java.net.Inet4Address
import java.security.SecureRandom

/** Server endpoint bootstrap ONLY. Never selects a routed website's DNS, edits
 * TUN/AAAA policy or falls back from the user's selected resolver to system.
 * Numeric profile hosts need no query. Actual Steer endpoint currently takes v4;
 * IPv6 website targets remain independent of this endpoint limitation. */
internal class ServerBootstrapPolicy private constructor(val endpoint:PacketManagedDnsPlan.Endpoint,private val nativeDns:JSONObject?=null) {
    val requiresCore:Boolean get()=nativeDns!=null
    /** A private DNS ingress, not a replacement profile/route or a second TUN.
     * Custom DNS is copied verbatim. References to unavailable proxy detours
     * fail native validation rather than being silently redirected to Direct. */
    fun coreConfig(port:Int,user:String,password:String):JSONObject {
        require(port in 1..65535&&user.isNotEmpty()&&password.isNotEmpty())
        val dns=JSONObject(requireNotNull(nativeDns).toString())
        return JSONObject().put("dns",dns).put("inbounds",org.json.JSONArray().put(JSONObject()
            .put("type","socks").put("tag","mcrf-server-dns").put("listen","127.0.0.1").put("listen_port",port)
            .put("users",org.json.JSONArray().put(JSONObject().put("username",user).put("password",password)))))
            .put("outbounds",org.json.JSONArray().put(JSONObject().put("type","direct").put("tag","direct")))
            .put("route",JSONObject().put("final","direct").put("default_domain_resolver",dns.getString("final"))
                .put("rules",org.json.JSONArray().put(JSONObject().put("inbound",org.json.JSONArray().put("mcrf-server-dns")).put("action","hijack-dns"))))
    }
    fun ipv4(host:String,exchange:(PacketManagedDnsPlan.Endpoint,ByteArray)->ByteArray):String {
        // Same reserved-loopback treatment as ProfileConfig, not a DNS fallback.
        if(host.equals("localhost",true))return "127.0.0.1"
        RouteRules.literal(host)?.let {literal->
            require(literal is Inet4Address){"Внешний IPv6 сервера Steer ещё не поддержан этим пакетным мостом. IPv6 сайтов не отключён."}
            return requireNotNull(literal.hostAddress)
        }
        require(host.length in 1..253&&host.split('.').all {label->label.length in 1..63&&label.first().isLetterOrDigit()&&label.last().isLetterOrDigit()&&label.all {it in 'a'..'z'||it in 'A'..'Z'||it in '0'..'9'||it=='-'}}){"SERVER_BOOTSTRAP_HOST"}
        val query=PacketDnsWire.query(host,1,SecureRandom().nextInt(65536))
        val answer=exchange(endpoint,query)
        return PacketDnsWire.addresses(query,answer).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
            ?:throw IllegalArgumentException("Выбранный DNS не вернул IPv4 сервера. Системный DNS автоматически не подставляется.")
    }
    override fun toString()="ServerBootstrapPolicy(redacted)"
    companion object {
        fun capture(state:JSONObject):ServerBootstrapPolicy {
            // Same visible-basic-DNS semantics as app-generated dispatcher
            // configs. The removed legacy opt-in must NOT hide the selected
            // server-address DNS or turn it into an implicit system fallback.
            val advanced=DirectDnsFamily.settings(state,true,false)
            if(advanced.optBoolean("enabled")&&advanced.optString("custom").isNotBlank()){
                try {
                    AdvancedDnsConfig.validate(advanced)
                    return ServerBootstrapPolicy(PacketManagedDnsPlan.Endpoint("native","",53,"",""),JSONObject(advanced.getString("custom")))
                }catch(error:org.json.JSONException){throw IllegalArgumentException("SERVER_BOOTSTRAP_CUSTOM_DNS",error)}
            }
            val settings=if(advanced.optBoolean("enabled"))advanced.getJSONObject("local")else JSONObject().put("provider","Системный").put("protocol","Системный")
            val value=DnsConfig.build(settings,"bootstrap","bootstrap")
            val type=if(value.getString("type")=="local")"system"else value.getString("type")
            require(!value.has("domain_resolver")){"SERVER_BOOTSTRAP_RECURSION"}
            require(type in setOf("system","udp","tcp","tls","https","quic","h3")){"SERVER_BOOTSTRAP_PROTOCOL"}
            val host=value.optString("server")
            require(type=="system"||RouteRules.literal(host)!=null){"SERVER_BOOTSTRAP_RECURSION"}
            val native=if(type in setOf("quic","h3"))JSONObject().put("servers",org.json.JSONArray().put(value)).put("final","bootstrap") else null
            return ServerBootstrapPolicy(PacketManagedDnsPlan.Endpoint(type,host,value.optInt("server_port",53),value.optString("path"),value.optJSONObject("tls")?.optString("server_name").orEmpty()),native)
        }
    }
}
