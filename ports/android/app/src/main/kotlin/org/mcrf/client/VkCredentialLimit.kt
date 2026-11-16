package org.mcrf.client

/** Trusted child status only. Never retain provider messages, hashes or credentials. */
internal data class VkCredentialLimit(val kind: Kind,val seconds: Int) {
    enum class Kind { FLOOD, RATE, CAPTCHA }
    class Blocked(val limit:VkCredentialLimit):java.io.IOException(limit.explanation())
    class AwaitingServerPing:java.io.IOException("VK Flood: перед подключением проверьте пинг этого сервера.")
    fun explanation()=if(kind==Kind.CAPTCHA)"VK временно ограничил проверки капчи. Попробуйте подключиться позже."else NETWORK_MESSAGE

    companion object {
        const val NETWORK_MESSAGE="VK временно ограничил запросы с вашей сети. Попробуйте подключиться через 10–20 минут."
        private val event=Regex("MCRF_VK_LIMIT\\|(FLOOD|RATE|CAPTCHA)\\|([1-9][0-9]{0,3})")
        private val pause=Regex("Пауза ([1-9][0-9]{0,3}) секунд")
        private val code=Regex("error_code[=:]\\s*(6|9|29)(?![0-9])")
        fun read(raw:String):VkCredentialLimit? {
            if(raw.length>8192)return null
            val line=raw.removePrefix("[CSQTT] child error: ")
            event.matchEntire(line)?.let {m->
                val seconds=m.groupValues[2].toInt()
                return if(seconds<=3600)VkCredentialLimit(Kind.valueOf(m.groupValues[1]),seconds)else null
            }
            // Compatibility with installed pre-event clients. A locally cached
            // lockout is NOT another provider response and must not extend TTL.
            if("активна глобальная пауза" in line||"активна общая пауза" in line)return null
            val credential=line.startsWith("[КРЕД #")&&"] Ошибка:" in line
            val legacy=line.startsWith("[STREAM ")&&"[VK Auth]" in line&&"VK API error:" in line
            if(!credential&&!legacy)return null
            val number=code.find(line)?.groupValues?.get(1)
            val kind=when {
                "Flood ограничение (error_code=" in line||number=="9"->Kind.FLOOD
                "Превышен лимит запросов VK (Rate limit: error_code=" in line||number in setOf("6","29")->Kind.RATE
                credential&&"VK" in line&&line.contains("Flood control",true)->Kind.FLOOD
                credential&&"VK" in line&&line.contains("Rate limit",true)->Kind.RATE
                "Превышен лимит сессии капчи VK (Captcha error_limit)" in line->Kind.CAPTCHA
                else->return null
            }
            val seconds=pause.find(line)?.groupValues?.get(1)?.toInt()?:if(kind==Kind.FLOOD)60 else 30
            return if(seconds<=3600)VkCredentialLimit(kind,seconds)else null
        }
    }
}

/** Store verifies boot identity; wall-clock edits within a boot cannot bypass TTL. */
internal data class VkCredentialPause(val kind:VkCredentialLimit.Kind,val untilWall:Long,val untilElapsed:Long) {
    fun remaining(wall:Long,elapsed:Long):VkCredentialLimit? {
        val millis=untilElapsed-elapsed
        if(millis<=0||millis>3_600_000)return null
        return VkCredentialLimit(kind,((millis+999)/1000).toInt())
    }
    companion object {
        fun start(limit:VkCredentialLimit,wall:Long,elapsed:Long)=VkCredentialPause(limit.kind,wall+limit.seconds*1000L,elapsed+limit.seconds*1000L)
    }
}
