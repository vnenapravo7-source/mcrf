package org.mcrf.client

/** Local evidence only. Configuration, reported readiness and successful traffic are different facts. */
object LocalDiagnostics {
    enum class Area(val label: String) { NETWORK("Сеть"), VPN("Системный VPN"), ENGINE("Движок"), DNS("DNS"), ROUTING("Маршрутизация") }
    enum class State { AVAILABLE, ACTIVE, CONFIGURED, PENDING, FAILED, UNAVAILABLE, UNKNOWN }
    data class Evidence(val area: Area, val state: State, val message: String, val timestamp: Long?, val source: String) {
        fun stale(now: Long) = timestamp != null && (now < timestamp || now - timestamp > FRESH_MS)
    }
    const val FRESH_MS = 120_000L
    const val PRIVACY = "Локальная диагностика без сетевых запросов. Показаны источник и время наблюдения. Настроенный DNS/маршрут и готовность движка не доказывают доступность сайтов или отсутствие утечек. Нет автоматической отправки."
    private val evidence = mutableMapOf<Area, Evidence>()

    @Synchronized fun network(available: Boolean?, validated: Boolean?, systemVpn: Boolean?, at: Long) {
        evidence[Area.NETWORK] = Evidence(Area.NETWORK, when(available) { true -> State.AVAILABLE; false -> State.UNAVAILABLE; null -> State.UNKNOWN },
            when { available == null -> "Сведения о сети недоступны."; available == false -> "Доступная сеть не обнаружена.";
                validated == true -> "Android сообщает о проверенном доступе в интернет."; else -> "Сеть есть; доступ в интернет Android не подтвердил." }, at, "ConnectivityManager · наблюдение")
        evidence[Area.VPN] = Evidence(Area.VPN, when(systemVpn) { true -> State.ACTIVE; false -> State.UNAVAILABLE; null -> State.UNKNOWN },
            when(systemVpn) { true -> "Android видит VPN. Принадлежность MCRF и прохождение трафика этим не доказаны.";
                false -> "Android не сообщает об активном системном VPN."; null -> "Системный статус VPN недоступен." }, at, "ConnectivityManager · наблюдение")
    }
    /** The raw status is classified, never displayed or retained. */
    @Synchronized fun serviceStatus(status: String, at: Long) {
        val state = when {
            ConnectionPresentation.failed(status) -> State.FAILED
            ConnectionPresentation.pending(status) -> State.PENDING
            ConnectionPresentation.connected(status) -> State.ACTIVE
            status == "Отключено" -> State.UNAVAILABLE
            else -> State.UNKNOWN
        }
        evidence[Area.ENGINE] = Evidence(Area.ENGINE, state, when(state) {
            State.ACTIVE -> "Служба MCRF сообщает о подключении. Передача трафика отдельно не проверена."
            State.PENDING -> "Служба MCRF устанавливает соединение."
            State.FAILED -> "Служба MCRF сообщает об ошибке или приостановке трафика. Откройте события ниже."
            State.UNAVAILABLE -> "Служба MCRF сообщает: отключено."
            else -> "Готовность движка неизвестна."
        }, at, "Текущий статус службы MCRF · наблюдение")
    }
    @Synchronized fun configuration(dnsServers: Int, routeRules: Int, at: Long) {
        evidence[Area.DNS] = Evidence(Area.DNS, if(dnsServers > 0) State.CONFIGURED else State.UNKNOWN,
            if(dnsServers > 0) "В собранной конфигурации DNS-серверов: $dnsServers. Ответы DNS не проверены."
            else "DNS-серверы в наблюдаемой конфигурации не обнаружены; работа DNS неизвестна.", at, "Сводка собранной конфигурации")
        evidence[Area.ROUTING] = Evidence(Area.ROUTING, State.CONFIGURED,
            "В собранной конфигурации правил: $routeRules. Фактический путь пакетов не проверен.", at, "Сводка собранной конфигурации")
    }
    @Synchronized fun snapshot(): List<Evidence> = Area.entries.map { area -> evidence[area]
        ?: Evidence(area, State.UNKNOWN, "Нет наблюдений в этом процессе. Источник ещё не предоставил данные.", null, "Нет источника") }
}
