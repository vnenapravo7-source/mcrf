package org.mcrf.client

/** Display-only translation. The redacted original record remains in Entry.details. */
internal object LogMessagePresentation {
    private val metrics=linkedMapOf(
        "worker_start" to "Запуск фонового обработчика",
        "service_queue" to "Ожидание в очереди службы",
        "ui_prepare" to "Подготовка подключения",
        "ui_queue" to "Ожидание запуска в очереди",
        "masque_prepare" to "Подготовка MASQUE",
        "resolve" to "Разрешение адресов",
        "detour" to "Подготовка обходного выхода",
        "native_validate" to "Проверка параметров внутри движка",
        "direct_check" to "Проверка прямого доступа в интернет",
        "previous_core_close" to "Остановка предыдущего движка",
        "profile_read" to "Чтение профиля",
        "service_to_ready" to "Подключение с момента запуска службы",
        "tap_to_ready" to "Подключение с момента нажатия",
        "bs_preflight" to "Проверка ограничений сети",
        "bs_admission" to "Проверка маршрутов БС",
        "bs_carrier_ready" to "Запуск транспорта БС",
        "bs_control_check" to "Проверка трафика через БС",
        "wg_prepare_start" to "Подготовка и запуск WireGuard",
        "steer_prepare_start" to "Подготовка и запуск Steer",
        "carrier_start" to "Запуск транспорта БС",
        "routes_load" to "Чтение правил маршрутизации",
        "routes_compile" to "Подготовка правил маршрутизации",
        "optional_core_start" to "Запуск дополнительного движка",
        "worker_validate" to "Проверка параметров фонового движка",
        "tun_establish" to "Открытие системного VPN",
        "core_init" to "Подключение к службе движка",
        "mono" to "Метка системного времени",
        "pid" to "ID процесса"
    )
    private val metricPatterns=metrics.map {(key,label)->Regex("\\b${key}=([0-9]+)(ms)?\\b") to label}
    fun explain(text:String):String {
        var result=text
        metricPatterns.forEach {(pattern,label)->
            result=result.replace(pattern) {m->
                "$label: ${m.groupValues[1]}${if(m.groupValues[2].isNotEmpty())" мс"else""}"
            }
        }
        if(result.startsWith("IDLE · READY"))result=result.replaceFirst("IDLE · READY","Служба готова, подключение ещё не запущено")
        if(result.startsWith("CONNECTING ·"))result=result.replaceFirst("CONNECTING","Устанавливаем соединение")
        if(result.startsWith("CONNECTED ·"))result=result.replaceFirst("CONNECTED","Соединение установлено")
        return result
    }
    fun brief(message:String,source:String,level:LogEntries.Level,kind:LogEntries.Kind):String {
        val lower=message.lowercase(java.util.Locale.ROOT)
        val simple=when {
            source=="Восстановление"&&"backoff ·" in lower->"Ждём перед повтором"
            source=="Восстановление"&&"reconnecting ·" in lower->"Восстанавливаем соединение"
            source=="Восстановление"&&"failed ·" in lower->"Не удалось восстановить соединение"
            source=="Восстановление"&&"stopped ·" in lower->"VPN отключён"
            source=="Восстановление"&&"соединение установлено · ready" in lower->"VPN подключён"
            "бс подключён самостоятельно" in lower->"БС подключён"
            "системный vpn подключён" in lower->"VPN подключён"
            "turn" in lower&&("401" in lower||"авторизац" in lower)->"TURN-сервер не принял данные подключения. Обновите ссылку или выберите другой сервер."
            "flood" in lower||"vk временно ограничил" in lower->"VK временно ограничил подключения. Повторите через 10–20 минут."
            "у физической сети нет ipv6" in lower->"В этой сети недоступен прямой IPv6. Для прямых соединений используется IPv4."
            source.startsWith("Тайм",true)->metrics.entries.firstOrNull {message.contains(it.key+"=")||message.contains(it.value)}?.value?.let {"Этап: $it."}?:"Измерено время подготовки подключения."
            "idle · ready" in lower||"служба готова, подключение ещё" in lower->"Служба готова к подключению."
            "обновления движков" in lower&&("не завершена" in lower||"не удалось" in lower)->"Не удалось проверить обновления движков. Установленные движки сохранены."
            else->message
        }
        if(simple!=message)return simple
        val technical=Regex("(?:[A-Za-z_][A-Za-z_0-9]*=|\\b(?:pid|mono|errno|stacktrace|exception|inbound/|outbound/)\\b|\\bat [a-zA-Z][\\w.$]+\\()",RegexOption.IGNORE_CASE).containsMatchIn(message)
        if(!technical&&Regex("[А-Яа-яЁё]").containsMatchIn(message)) {
            val clean=message.lineSequence().firstOrNull {it.isNotBlank()}.orEmpty()
                .replace(Regex("\\s+")," ").trim()
            val firstSentence=Regex("[.!?](?=\\s|$)").find(clean)?.range?.last?.plus(1)
            val lead=firstSentence?.takeIf {it>=12}?.let(clean::take)?:clean
            if(lead.length<=100)return lead
            val prefix=lead.take(99)
            return prefix.substringBeforeLast(' ',prefix).trimEnd()+ "…"
        }
        val context=when(kind) {
            LogEntries.Kind.CONNECTION->"подключения"
            LogEntries.Kind.SERVERS->"проверки серверов"
            LogEntries.Kind.SUBSCRIPTIONS->"обновления подписки"
            LogEntries.Kind.DNS->"разрешения адресов DNS"
            LogEntries.Kind.ROUTING->"маршрутизации"
            LogEntries.Kind.TELEGRAM->"прокси Telegram"
            LogEntries.Kind.ENGINE->"сетевого движка"
            LogEntries.Kind.SYSTEM->"службы приложения"
            LogEntries.Kind.ANUBIS->"Anubis"
        }
        return when(level) {
            LogEntries.Level.ERROR->"Ошибка $context"
            LogEntries.Level.WARNING->"Предупреждение $context"
            LogEntries.Level.SUCCESS->"Операция $context выполнена."
            else->"Событие $context"
        }
    }

}
