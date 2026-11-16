package org.mcrf.client

/** Only observed startup markers; never includes native output, links or credentials. */
object CarrierStartupFailure {
    const val verificationRequired = "VK просит подтверждение. Открой подключение вручную и пройди проверку."
    fun isVerificationSignal(line:String):Boolean = line=="MCRF_CAPTCHA_REQUIRED"
    fun timeout(transportReady:Boolean,verificationSeen:Boolean):String = when {
        transportReady -> "Транспорт сообщил о готовности, но локальный прокси движка не прошёл проверку вовремя. Подключение не установлено."
        verificationSeen -> "Движок не сообщил о готовности вовремя. Во время запуска VK запрашивал проверку; это не доказывает причину таймаута. Подключение не установлено."
        else -> "Движок не сообщил о готовности транспорта вовремя. Причина не установлена: проверь сервер, параметры и доступность сервиса."
    }
}
