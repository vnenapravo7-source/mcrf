package org.mcrf.client

/** Only adapter-authored codes are displayed; no raw PPP identity or TLS dump. */
internal object SstpFailure {
    fun describe(engine:String,line:String):String? {
        if(engine!=SstpProfile.KIND||line.length>128||!line.startsWith("MCRF_ERROR "))return null
        val code=line.removePrefix("MCRF_ERROR ")
        val text=when(code) {
            "SSTP_CONFIG_ARGUMENTS","SSTP_CONFIG_READ","SSTP_CONFIG_SIZE","SSTP_CONFIG_OBJECT","SSTP_CONFIG_DUPLICATE","SSTP_CONFIG_VALUE","SSTP_CONFIG_FORMAT","SSTP_CONFIG_TRAILING","SSTP_CONFIG_HOST","SSTP_CONFIG_PORT","SSTP_CONFIG_CREDENTIALS","SSTP_CONFIG_LISTENER","SSTP_CONFIG_LOCAL_AUTH","SSTP_CONFIG_CA"->"Адаптер SSTP отклонил настройки подключения."
            "SSTP_TLS_UNKNOWN_AUTHORITY"->"Сертификат SSTP не выдан доверенным CA. Добавьте сертификат своего CA; проверка TLS не отключалась."
            "SSTP_TLS_NAME_MISMATCH"->"Имя в сертификате SSTP не совпадает с адресом или SNI."
            "SSTP_TLS_CERTIFICATE"->"Сертификат SSTP не прошёл проверку срока действия или цепочки доверия."
            "SSTP_TLS_HANDSHAKE"->"TLS-согласование SSTP не завершилось; проверьте адрес, порт и SNI."
            "SSTP_HTTP_HANDSHAKE"->"TLS установлен, но сервер не подтвердил HTTP-сеанс SSTP."
            "SSTP_CONTROL_HANDSHAKE"->"Сервер не подтвердил управляющий сеанс SSTP."
            "SSTP_AUTH_REJECTED"->"SSTP-сервер отклонил авторизацию PPP/MS-CHAPv2; проверьте логин и пароль."
            "SSTP_NETWORK_TIMEOUT"->"Истекло время подключения или согласования SSTP."
            "SSTP_NETSTACK"->"SSTP подключён, но не удалось подготовить локальный сетевой стек."
            "SSTP_LOCAL_LISTENER","SSTP_LOCAL_ACCEPT"->"Не удалось подготовить локальный SOCKS-вход SSTP."
            "SSTP_SESSION_CLOSED"->"SSTP-сервер завершил сеанс."
            "SSTP_SESSION_FAILED"->"Сеанс SSTP завершился с ошибкой, не относящейся к известным ошибкам сертификата или авторизации."
            "SSTP_CANCELLED"->"Подключение SSTP отменено."
            else->return null
        }
        return "$code · $text"
    }
}
