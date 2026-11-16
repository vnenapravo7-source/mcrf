package org.mcrf.client

/** Short user-facing limits, separate from native capability validation and test claims. */
object PrimaryCorePresentation {
    fun details(kind:String):String=when(kind){
        "sing-box"->"Плюсы: IPv6, VPN/прокси, гибкие правила.\nМинусы: модуль занимает больше места."
        "steer"->"Плюсы: компактный, работает без sing-box.\nМинусы: только IPv4; без локального прокси, автовыбора серверов и DNS отдельных правил.\nНет встроенной поддержки TUIC, AnyTLS, NaiveProxy и SSH. WireGuard/AmneziaWG — отдельный модуль, пока проходит проверку."
        else->throw IllegalArgumentException("Неизвестный основной движок.")
    }
    fun confirmation(kind:String):String=when(kind){
        "steer","sing-box"->"Проверим совместимость сохранённых подключений и настроек перед переключением."
        else->throw IllegalArgumentException("Неизвестный основной движок.")
    }
    /** One companion supplies both protocols; it is offered only when not installed. */
    fun offerWgAddon(kind:String,addonInstalled:Boolean)=kind=="steer"&&!addonInstalled
}
