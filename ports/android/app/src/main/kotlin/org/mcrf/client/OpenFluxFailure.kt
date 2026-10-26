package org.mcrf.client

/** Fresh wrapper's finite failure IPC, not a URL/HTML/cookie transport. */
internal object OpenFluxFailure {
    fun describe(engine: String, line: String): String? {
        if (engine != "openflux") return null
        return when (line) {
            "MCRF_OPENFLUX_FAILURE provider_verification_required" ->
                "Провайдер OpenFlux запросил проверку в браузере. Этот модуль пока не поддерживает её показ; подключение остановлено без обхода проверки."
            "MCRF_OPENFLUX_FAILURE setup_page_unsupported" ->
                "Провайдер OpenFlux запросил встроенную страницу настройки. Её показ пока не поддерживается; подключение остановлено без передачи страницы в окно VK."
            "MCRF_OPENFLUX_FAILURE exit_verification_proxy_unsupported" ->
                "Проверка OpenFlux должна выполняться с адреса сервера через прокси туннеля. Такой режим проверки пока не поддерживается; другой маршрут не используется."
            "MCRF_OPENFLUX_FAILURE verification_page_rejected" ->
                "Страница проверки OpenFlux отклонена: нужен безопасный HTTPS-адрес."
            "MCRF_OPENFLUX_FAILURE verification_reply_failed" ->
                "Не удалось передать ответ проверки в текущий транспорт OpenFlux. Подключение остановлено; ответ не переносится в другое подключение."
            "MCRF_OPENFLUX_FAILURE verification_document_failed" ->
                "Не удалось безопасно получить встроенную страницу текущей проверки OpenFlux. Подключение остановлено; содержимое страницы не записывается в журнал."
            else -> null
        }
    }
}
