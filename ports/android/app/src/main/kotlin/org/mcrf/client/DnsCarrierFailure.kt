package org.mcrf.client

/** Closed diagnostic vocabulary. Never display an arbitrary native IPC payload. */
internal object DnsCarrierFailure {
    fun stage(engine:String,line:String):String? {
        if(engine==MasterDnsProfile.KIND)return MasterDnsFailure.stage(engine,line)
        if(engine==SlipstreamProfile.KIND)return when(line) {
            "MCRF_STAGE CERTIFICATE_MISMATCH"->"Сертификат Slipstream не совпадает с закреплённым. Подключение остановлено; обход проверки запрещён."
            "MCRF_STAGE CERTIFICATE_REQUIRED"->"Slipstream не получил сертификат сервера. Подключение остановлено."
            "MCRF_STAGE REMOTE_STREAM_UNAVAILABLE"->"Slipstream не смог открыть удалённый SOCKS-поток. Смотрите причину завершения туннеля."
            else->null
        }
        if(engine!=VayDnsProfile.KIND)return null
        return when(line) {
            "MCRF_STAGE resolver-reconnect" -> "Не удалось подготовить DNS-резолвер; повтор с увеличением интервала."
            "MCRF_STAGE session-reconnect" -> "Не удалось установить защищённую DNS-сессию; повтор с увеличением интервала."
            "MCRF_STAGE session-lost" -> "Защищённая DNS-сессия прервалась; повтор с увеличением интервала."
            "MCRF_STAGE backend-reconnect" -> "Удалённый SOCKS-сервис за DNS-туннелем не завершил согласование; повтор с увеличением интервала."
            else -> null
        }
    }
    fun describe(engine: String, line: String): String? {
        if(engine=="olcrtc")return olc(line)
        if(engine=="dnstt")return dnstt(line)
        if(engine==SlipstreamProfile.KIND)return slipstream(line)
        if (engine != "vaydns" || line.length > 256 || !line.startsWith("MCRF_ERROR ")) return null
        val code = line.removePrefix("MCRF_ERROR ").substringBefore(':')
        val explanation = when (code) {
            "CONFIG_ARGUMENTS" -> "Не передан закрытый файл настроек адаптера."
            "CONFIG_READ" -> "Не удалось прочитать закрытый файл настроек адаптера."
            "CONFIG_INVALID" -> "Адаптер отклонил формат или значения настроек VayDNS."
            "REMOTE_SOCKS_AUTH_REJECTED" -> "Удалённый SOCKS-сервер отклонил авторизацию; автоматические попытки остановлены. Проверьте логин и пароль."
            "REMOTE_SOCKS_PROTOCOL_REQUIRED" -> "VayDNS-сервер должен направлять потоки в SOCKS5. Удалённый сервис ответил другим протоколом."
            "VAYDNS_SESSION_STOPPED" -> "Сессия VayDNS остановлена; проверьте предыдущие этапы подключения."
            else -> return null
        }
        return "$code · $explanation"
    }
    private fun olc(line:String):String? {
        if(!line.startsWith("MCRF_ERROR "))return null
        Regex("^MCRF_ERROR OLC_HTTP_([1-5][0-9]{2})$").matchEntire(line)?.let {match->
            return "OLC_HTTP_${match.groupValues[1]} · Провайдер OLC вернул HTTP ${match.groupValues[1]}. Тело ответа с возможными токенами скрыто."
        }
        val code=line.removePrefix("MCRF_ERROR ")
        val explanation=when(code){
            "OLC_CONFIG_ARGUMENTS","OLC_CONFIG_READ"->"Не удалось получить закрытые настройки OLC."
            "OLC_CONFIG_REJECTED"->"OLC отклонил формат настроек, адрес или параметры транспорта."
            "OLC_NETWORK_TIMEOUT"->"OLC сообщил сетевой таймаут при установлении или работе сессии."
            "OLC_TLS_UNTRUSTED"->"Сертификат провайдера OLC не подтверждён доверенным центром."
            "OLC_TLS_HOSTNAME"->"Имя сервера OLC не совпадает с TLS-сертификатом."
            "OLC_TLS_CERTIFICATE"->"OLC отклонил недействительный TLS-сертификат."
            "OLC_PEER_REJECTED"->"Удалённая сторона OLC отклонила защищённое согласование."
            "OLC_PROTOCOL_VERSION"->"Поколения протокола OLC у клиента и сервера несовместимы."
            "OLC_CHALLENGE_MISMATCH","OLC_CHALLENGE_REQUIRED"->"Удалённая сторона OLC не подтвердила запрос защищённой сессии."
            "OLC_PEER_PROTOCOL"->"Удалённая сторона OLC прислала некорректное сообщение протокола."
            "OLC_CONNECTION_REFUSED"->"Сервер отказал в сетевом соединении OLC."
            "OLC_PEER_CLOSED"->"Удалённая сторона закрыла соединение OLC."
            "OLC_DNS_FAILED"->"Не удалось разрешить адрес при подключении OLC."
            "OLC_SESSION_FAILED"->"Сессия OLC завершилась с ошибкой. Провайдер не сообщил безопасную уточнённую причину; параметры комнаты и токен скрыты."
            else->return null
        }
        return "$code · $explanation"
    }
    private fun dnstt(line:String):String? {
        val code=line.removePrefix("MCRF_ERROR ")
        if(!line.startsWith("MCRF_ERROR "))return null
        val explanation=when(code){
            "DNSTT_CONFIG_READ"->"Не удалось прочитать закрытые настройки DNSTT."
            "DNSTT_CONFIG_REJECTED"->"DNSTT отклонил формат, адреса или значения закрытых настроек."
            "DNSTT_HANDSHAKE_TIMEOUT"->"Истекло ожидание защищённого согласования DNSTT. Проверьте DNS-резолвер и сервер."
            "DNSTT_NOISE_HANDSHAKE_FAILED"->"Защищённое согласование DNSTT не завершилось. Возможны ошибка ключа или обрыв DNS-транспорта; точная причина не подтверждена."
            "DNSTT_SESSION_CLOSED"->"Сессия DNSTT закрылась. Локальный приём соединений остановлен."
            else->return null
        }
        return "$code · $explanation"
    }
    private fun slipstream(line:String):String? {
        if(line.length>128||!line.startsWith("MCRF_ERROR "))return null
        val code=line.removePrefix("MCRF_ERROR ")
        val explanation=when(code) {
            "CONFIG_ARGUMENTS","CONFIG_READ","CONFIG_SIZE","CONFIG_OBJECT","CONFIG_DUPLICATE","CONFIG_VALUE","CONFIG_FORMAT","CONFIG_TRAILING","CONFIG_DOMAIN","CONFIG_ENDPOINT","CONFIG_KEEPALIVE","CONFIG_CREDENTIALS"->"Адаптер Slipstream отклонил закрытые настройки."
            "CERTIFICATE_FORMAT","CERTIFICATE_REQUIRED"->"Нужен один корректный PEM-сертификат самого сервера Slipstream."
            "CERTIFICATE_MISMATCH"->"Сертификат сервера не совпадает с закреплённым; подключение остановлено без обхода проверки."
            "CERTIFICATE_WRITE","PRIVATE_DIRECTORY","PRIVATE_SOCKET_PATH","PRIVATE_SOCKET_PERMISSIONS"->"Не удалось подготовить защищённый локальный канал Slipstream."
            "BACKEND_START","BACKEND_DIAGNOSTIC_PIPE","EXECUTABLE_PATH"->"Не удалось запустить клиент Slipstream из отдельного модуля."
            "BACKEND_EXIT_OR_CANCEL"->"Дочерний клиент Slipstream остановился до готовности туннеля."
            "TUNNEL_NOT_READY"->"За 40 с не появился закрытый вход Slipstream."
            "TUNNEL_AUTHENTICATION_OR_NETWORK"->"Удалённый SOCKS не ответил через защищённый туннель; проверьте DNS-резолвер, делегирование домена и доступность сервера. Сертификат не отключался."
            "REMOTE_SOCKS_AUTH"->"Удалённый SOCKS отверг авторизацию; проверьте логин и пароль. Автоматический перебор не выполняется."
            "REMOTE_SOCKS_PROTOCOL"->"Сервер Slipstream должен направлять потоки в SOCKS5; ответ удалённого сервиса несовместим."
            "LOCAL_LISTEN","LOCAL_ACCEPT"->"Не удалось открыть локальный SOCKS-вход Slipstream."
            else->if(code.matches(Regex("BACKEND_EXIT_(?:-1|[0-9]{1,3})")))"Клиент Slipstream завершился; код указан выше."else return null
        }
        return "$code · $explanation"
    }
}
