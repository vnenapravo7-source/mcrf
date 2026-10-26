package org.mcrf.client

import org.json.JSONObject

/** Keep display, probe and dispatcher metadata out of the native protocol schema. */
internal object DnsttPrivateConfig {
    fun create(profile:JSONObject,port:Int,user:String,password:String):JSONObject {
        require(profile.optString("type")=="dnstt")
        val validated=CarrierProfile.fromJson("private-config",JSONObject(profile.toString())).carrier!!
        val result=JSONObject()
        for(key in listOf("domain","key","doh","dot","udp","username","password")) {
            if(validated.has(key))result.put(key,validated.getString(key))
        }
        require(port in 1..65535&&user.isNotEmpty()&&password.isNotEmpty()&&
            user.toByteArray(Charsets.UTF_8).size<=255&&password.toByteArray(Charsets.UTF_8).size<=255)
        return result.put("socks","127.0.0.1:$port").put("socks_user",user).put("socks_pass",password)
    }
}
