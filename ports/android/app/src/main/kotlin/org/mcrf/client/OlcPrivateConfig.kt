package org.mcrf.client

import org.json.JSONObject

internal object OlcPrivateConfig {
    fun create(profile:JSONObject,port:Int,user:String,password:String,deviceId:String):JSONObject{
        require(profile.optString("type")=="olcrtc")
        val validated=CarrierProfile.fromJson("private-config",JSONObject(profile.toString())).carrier!!
        require(port in 1..65535&&user.isNotEmpty()&&password.isNotEmpty()&&deviceId.isNotBlank()&&
            user.toByteArray(Charsets.UTF_8).size<=255&&password.toByteArray(Charsets.UTF_8).size<=255)
        val result=JSONObject()
        for(key in listOf("provider","transport","room","key","token","channel","dns","options"))if(validated.has(key))result.put(key,validated.get(key))
        return result.put("socks","127.0.0.1:$port").put("socks_user",user).put("socks_pass",password).put("device_id",deviceId)
    }
}
