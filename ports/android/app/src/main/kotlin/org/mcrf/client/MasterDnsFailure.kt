package org.mcrf.client

internal object MasterDnsFailure {
    fun stage(engine:String,line:String):String? {
        if(engine!=MasterDnsProfile.KIND)return null
        return when(line) {
            "MCRF_STAGE MASTERDNS_MTU"->"MasterDnsVPN проверяет MTU выбранных доменов и резолверов."
            "MCRF_STAGE MASTERDNS_MTU-FAILED"->"Проверка MTU MasterDnsVPN не получила подходящего ответа. Проверьте домен, резолверы и совпадение ключа с сервером; это не подтверждённый отказ авторизации."
            "MCRF_STAGE MASTERDNS_SESSION"->"MasterDnsVPN согласовывает сессию с сервером."
            "MCRF_STAGE MASTERDNS_SESSION-FAILED"->"MasterDnsVPN не завершил согласование сессии; повтор выполняется с увеличением интервала."
            "MCRF_STAGE MASTERDNS_BUSY"->"MasterDnsVPN-сервер сообщил о занятости или отклонил создание сессии; движок соблюдает интервал повторной попытки."
            "MCRF_STAGE MASTERDNS_LOST"->"Сессия MasterDnsVPN потеряна; выполняется повторное согласование с увеличением интервала."
            "MCRF_STAGE MASTERDNS_STOP_FORCED"->"MasterDnsVPN не завершил внутренние задачи за 2 с; процесс остановлен без дочерних процессов."
            else->null
        }
    }
    fun describe(engine:String,line:String):String? {
        if(engine!=MasterDnsProfile.KIND||line.length>128||!line.startsWith("MCRF_ERROR "))return null
        val code=line.removePrefix("MCRF_ERROR ")
        val description=when(code) {
            "MASTERDNS_CONFIG_ARGUMENTS","MASTERDNS_CONFIG_READ","MASTERDNS_CONFIG_SIZE","MASTERDNS_CONFIG_OBJECT","MASTERDNS_CONFIG_DUPLICATE","MASTERDNS_CONFIG_VALUE","MASTERDNS_CONFIG_FORMAT","MASTERDNS_CONFIG_TRAILING"->"Не удалось прочитать однозначные закрытые настройки MasterDnsVPN."
            "MASTERDNS_ENDPOINTS_REQUIRED","MASTERDNS_DOMAIN","MASTERDNS_RESOLVER"->"Проверьте делегированные домены и числовые IP:порты DNS-резолверов."
            "MASTERDNS_ENCRYPTION","MASTERDNS_COMPRESSION","MASTERDNS_WORKERS"->"Проверьте ключ, явный метод шифрования, сжатие и локальное число рабочих потоков."
            "MASTERDNS_LOCAL_AUTH"->"Отклонены параметры защищённого локального SOCKS-входа."
            "MASTERDNS_PRIVATE_DIRECTORY","MASTERDNS_PRIVATE_RESOLVERS"->"Не удалось подготовить закрытые настройки резолверов."
            "MASTERDNS_CONFIG_REJECTED"->"Оригинальный движок MasterDnsVPN отклонил настройки; другой DNS-протокол не подставлялся."
            "MASTERDNS_CODEC"->"Не удалось подготовить выбранный метод шифрования MasterDnsVPN."
            "MASTERDNS_ENDPOINT_MAP"->"Не удалось составить пары доменов и DNS-резолверов MasterDnsVPN."
            "MASTERDNS_NETWORK_TIMEOUT"->"Истекло время сетевой операции MasterDnsVPN."
            "MASTERDNS_LOCAL_LISTENER"->"Не удалось открыть локальный SOCKS-вход MasterDnsVPN."
            "MASTERDNS_LOCAL_UDP"->"Не удалось открыть локальный UDP-сокет для DNS-туннеля MasterDnsVPN."
            "MASTERDNS_RUNTIME"->"Оригинальный движок MasterDnsVPN завершился с ошибкой; смотрите последний этап подключения."
            else->return null
        }
        return "$code · $description"
    }
}
