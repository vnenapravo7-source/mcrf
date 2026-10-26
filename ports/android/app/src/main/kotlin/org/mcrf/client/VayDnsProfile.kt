package org.mcrf.client

import org.json.JSONObject

/** Explicit JSON import only: upstream does not define a public share-URI. */
internal object VayDnsProfile {
    const val KIND="vaydns"
    const val REPOSITORY="net2share/vaydns"
    const val COMMIT="a0ff70110d5e96686ab8f4c8e1c44cd09be07a75"
    fun compatible(kind:String?,repository:String?,commit:String?,contract:Int)=
        kind==KIND && repository==REPOSITORY && commit==COMMIT && contract==1
    private val fields=setOf("type","name","domain","key","doh","dot","udp","username","password")
    fun fromJson(source:String,raw:JSONObject):ServerImport.Server {
        require(raw.optString("type")==KIND && raw.keys().asSequence().all {it in fields}) {"Неизвестное поле профиля VayDNS."}
        // Domain/key/resolver/SOCKS credential syntax is shared with DNSTT,
        // but the wire protocol and optional native module are NOT substituted.
        val validation=JSONObject(raw.toString()).put("type","dnstt")
        if(!validation.has("name"))validation.put("name","VayDNS")
        val checked=try {CarrierProfile.fromJson(source,validation)}catch(error:IllegalArgumentException){
            throw IllegalArgumentException(error.message?.replace("DNSTT","VayDNS")?:"Проверьте профиль VayDNS.")
        }
        val profile=JSONObject(requireNotNull(checked.carrier).toString()).put("type",KIND)
        return ServerImport.Server(source,checked.name,KIND,checked.host,checked.port,null,null,null,profile)
    }
    fun privateConfig(profile:JSONObject,port:Int,user:String,password:String):JSONObject {
        require(port in 1..65535)
        val saved=JSONObject(profile.toString()).apply {remove("probe_only");remove("outbound_tag")}
        val checked=requireNotNull(fromJson(saved.toString(),saved).carrier)
        val result=JSONObject()
        // Display metadata/probe flags/device IDs must not leak into the
        // closed native schema or override its per-process local credentials.
        fields.filter {it !in setOf("type","name") && checked.has(it)}.forEach {result.put(it,checked.get(it))}
        return result.put("socks","127.0.0.1:$port").put("socks_user",user).put("socks_pass",password)
    }
}
