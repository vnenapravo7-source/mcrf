package org.mcrf.client

/** User explanations only. Never changes the adapter's admission decision or echoes configuration values. */
object SteerCompatibilityHints {
    private fun detail(code:String,field:String):String {
        val reason=code.takeIf {it.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))}?:"UNSUPPORTED"
        val path=field.takeIf {it.length<=96&&it.matches(Regex("(snapshot|routing|tun|coreTuning|localProxyAuth)(?:\\.[A-Za-z][A-Za-z0-9_]{0,40}|\\[[0-9]{1,4}\\])*"))}
        return if(path==null)reason else "$reason · $path"
    }
    fun describe(code:String,field:String):String = when(code) {
        "TUN_MTU","TUN_ADDRESS" -> "Для Steer нужны MTU 1500 и адрес TUN 10.89.0.2/32. Измени их в расширенных настройках VPN."
        "TUN_UNSUPPORTED" -> when(field.substringAfterLast('.')) {
            "ipv6" -> "Steer пока не обрабатывает IPv6. Отключи IPv6 в настройках TUN или выбери sing-box."
            "mixedEnabled","httpProxy","shareLan" -> "Steer пока не предоставляет локальный прокси и раздачу VPN. Отключи их явно или выбери sing-box."
            else -> "Steer пока не поддерживает дополнительные исключения TUN. Проверь обход частных сетей и прямых IP."
        }
        "DNS_LISTENER","CUSTOM_INBOUNDS" -> "Собственные входы и отдельный локальный DNS-порт пока требуют sing-box."
        "PRESET","GEO_SELECTORS","GEO_RULES" -> "Готовые списки, GeoIP и GeoSite пока требуют sing-box. Обычные доменные и IPv4-правила доступны в Steer."
        "GEO_MANIFEST_REQUIRED" -> "Для Steer нет подготовленного снимка GeoIP/GeoSite. Автоматически ничего не скачивается и не меняется; можно выбрать sing-box."
        "GEO_CONFIG","GEO_BINDING","GEO_SELECTOR","GEO_IDENTITY","GEO_BYTES","GEO_MANIFEST_LIMIT" -> "Геоданные не совпадают с выбранными категориями или превышают ограничения Steer. Настройки сохранены; можно выбрать sing-box."
        "NATIVE_MATCH","NATIVE_MATCH_FIELD","NATIVE_MATCH_TYPE","NATIVE_MATCH_MODE","NATIVE_MATCH_EMPTY","NATIVE_MATCH_LIMIT","NATIVE_MATCH_PORT","NATIVE_MATCH_NETWORK","NATIVE_MATCH_PACKAGE","NATIVE_MATCH_SHAPE" -> "Точное правило содержит неподдержанное условие Steer. Правило не будет упрощено автоматически; выбери sing-box или измени его явно."
        "NATIVE_FLOW_PROXY","ORIGINAL_PROXY_FLOW_UNAVAILABLE" -> "Для этих правил нужны исходные адрес и порт клиента локального прокси. Пока Steer не передаёт их полностью, сочетание недоступно. Настройки не менялись."
        "ORIGINAL_PROXY_FLOW_NOT_ADVERTISED" -> "Установленный модуль Steer не подтверждает проверку исходного клиента и запроса локального прокси для точных правил. Ни приложение, ни правила не будут подменены."
        "ORIGINAL_SOCKET_FLOW_NOT_ADVERTISED" -> "Установленный модуль Steer не заявляет правила исходного соединения. Адресные правила или номер версии сами по себе этого не подтверждают."
        "APP_CONDITION","APP_SCOPE","FORCED_APPS" -> "Этот режим правил по приложениям пока требует sing-box. Steer принимает простые исключения приложений."
        "RULE_DNS" -> "Отдельный DNS для правила пока требует sing-box. В Steer используется общий DNS."
        "ADVANCED_DNS" -> "Расширенные DNS-фильтры и FakeIP пока требуют sing-box. Общий UDP, DoH и DoT доступны в Steer."
        "DNS_PROTOCOL","DNS_PROVIDER","DNS_ADDRESS" -> "Проверь общий DNS: для Steer нужны UDP, DoH или DoT и корректный адрес сервера."
        "IPV4_DNS_MODEL","IPV4_DNS_NOT_ADVERTISED" -> "Установленный Steer не подтверждает IPv4-совместимость DNS. IPv6 в TUN и правила маршрутизации не отключаются автоматически; настройки сохранены."
        "REMOTE_ADBLOCK","REMOTE_REFRESH","REMOTE_PROFILES" -> "Импортированные наборы маршрутов и встроенный DNS-фильтр рекламы пока требуют sing-box."
        "CERTIFICATE_STORE" -> "Steer использует системные сертификаты Android. Другие хранилища пока требуют sing-box."
        "CORE_TUNING" -> "Есть несовместимые параметры ядра: проверь TLS, MUX, фрагментацию и QUIC в расширенных настройках. Они не будут отключены автоматически."
        "INVERT_CONDITION" -> "Steer поддерживает инверсию только для адресного правила с выходом VPN. Измени правило явно или выбери sing-box."
        "PROXY_NOT_SUPPORTED" -> "Steer работает в режиме VPN. Для режима «Только прокси» выбери sing-box."
        "IPV4_DNS_CONSENT" -> "Подтверди выбор Steer и его модели DNS: сейчас только IPv4."
        "DNS_MODEL_CONSENT" -> "Подтверди новую модель DNS при выборе Steer. Старое согласие на IPv4 её не заменяет."
        "PROXY_APP_IDENTITY" -> "Локальный прокси пока нельзя сочетать с правилами приложений в Steer: нужен UID исходного клиента. Выбери другой движок либо явно измени настройки; автоматически ничего не отключается."
        "ADDITIONAL_FEATURE" -> "Автовыбор серверов или включённые по умолчанию дополнительные TLS/Xray-функции пока несовместимы со Steer."
        "RULE_LIMIT","APP_LIMIT","SNAPSHOT_SIZE","SNAPSHOT_DEPTH","SNAPSHOT_COMPLEXITY" -> "Для Steer слишком большой или вложенный набор настроек и правил. Сократи его либо выбери sing-box."
        "SNAPSHOT_JSON","SNAPSHOT_GUARD_CONTRACT" -> "Не удалось безопасно прочитать снимок настроек Steer. Исходные настройки сохранены; подключение с упрощёнными правилами не запускается."
        else -> "Некоторые сохранённые параметры не поддерживаются этой версией Steer. Проверь настройки или выбери sing-box.\nПричина: ${detail(code,field)}."
    }
}
