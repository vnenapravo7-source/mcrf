package org.mcrf.client

import java.net.URI
import org.json.JSONObject

/** Continue only an explicit rejected REST request, never a timeout or ambiguous call creation. */
class VkApiChallenge private constructor(val url:String,private val fields:Map<String,String>) {
    fun continuation(proof:String):Map<String,String> {
        require(proof.matches(Regex("[A-Za-z0-9._~+/=-]{1,8192}"))) {"VK вернул некорректное подтверждение"}
        return fields+mapOf("captcha_key" to "","success_token" to proof)
    }
    override fun toString()="VkApiChallenge(redacted)"
    companion object {
        fun continueOnce(response:JSONObject,solve:(VkApiChallenge)->String,send:(Map<String,String>)->JSONObject):JSONObject {
            if(response.optJSONObject("error")?.optInt("error_code")!=14)return response
            val challenge=parse(response)?:error("VK не предоставил безопасную страницу проверки")
            return send(challenge.continuation(solve(challenge)))
        }
        fun parse(response:JSONObject):VkApiChallenge? {
            if(response.has("response"))return null
            val error=response.optJSONObject("error")?:return null
            if(error.optInt("error_code")!=14)return null
            val url=error.optString("redirect_uri")
            val sid=error.optString("captcha_sid")
            if(!CarrierChallenge.allowed(url)||!sid.matches(Regex("[A-Za-z0-9_-]{1,256}")))return null
            val path=runCatching {URI(url).path.orEmpty().lowercase()}.getOrDefault("")
            if("captcha" !in path&&"not_robot" !in path)return null
            val fields=linkedMapOf("captcha_sid" to sid)
            for(key in listOf("captcha_ts","captcha_attempt")){
                val value=error.optString(key)
                if(value.isNotEmpty()){
                    if(!value.matches(Regex("[0-9]{1,20}")))return null
                    fields[key]=value
                }
            }
            return VkApiChallenge(url,fields)
        }
    }
}
