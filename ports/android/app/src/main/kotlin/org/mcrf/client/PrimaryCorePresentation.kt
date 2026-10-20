package org.mcrf.client

/** Short user-facing limits, separate from native capability validation and test claims. */
object PrimaryCorePresentation {
    fun details(kind:String):String=when(kind){
        "sing-box"->"Плюсы: IPv6, VPN/прокси, гибкие правила.\nМинусы: модуль занимает больше места."
        "steer"->"Плюсы: компактный, работает без sing-box.\nМинусы: только IPv4; без локального прокси, автовыбора серверов и DNS отдельных правил.\nНет встроенной поддержки TUIC, AnyTLS, NaiveProxy и SSH. WireGuard/AmneziaWG — отдельный модуль, пока проходит проверку."
        else->throw IllegalArgumentException("Неизвестный основной движок.")
    }
    fun confirmation(kind:String):String=when(kind){
        "steer"->"Steer работает без sing-box. Нет IPv6, локального прокси, TUIC, AnyTLS, NaiveProxy и SSH. WireGuard/AmneziaWG — отдельный модуль, пока проходит проверку; при установленном sing-box WG/AWG идёт через него. Дополнения Xray/TLS не расширяют Steer. Сначала проверим профиль и настройки — несовместимые не отбросим. Соединение нужно остановить."
        "sing-box"->"Движки поддерживают разные возможности. Сначала проверим профиль и настройки. Соединение нужно остановить."
        else->throw IllegalArgumentException("Неизвестный основной движок.")
    }
}
