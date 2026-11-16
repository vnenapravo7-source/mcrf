package org.mcrf.client

import java.util.Locale

/** Searches labels and child-setting terminology without reading profile secrets. */
object SettingsSearchIndex {
    private fun normalized(text:String)=text.lowercase(Locale.ROOT).replace('ё','е')
    fun terms(query:String)=normalized(query).trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    fun advancedAnchor(query:String):String {
        val words=normalized(query)
        return when {
            listOf("dns","днс","fakeip","aaaa","hosts","резолвер","кэш").any(words::contains)->"advanced-dns"
            listOf("xray","vless","reality","xhttp").any(words::contains)->"advanced-xray"
            listOf("tls","sni","utls","fragment","фрагмент").any(words::contains)->"advanced-tls"
            listOf("geo","гео").any(words::contains)->"advanced-geo"
            listOf("tun","mtu","ipv6","прокси","интерфейс","локальн").any(words::contains)->"advanced-tun"
            else->"advanced-core"
        }
    }
    fun hitCount(query:String,label:String)=terms(query).count(normalized(label)::contains)
    fun matches(query:String,words:String):Boolean {
        val haystack=normalized(words)
        return terms(query).all(haystack::contains)
    }
    fun keywords(page:String):String=when(page) {
        ClientSettingsNavigation.BETA->"beta бета запись трафика метаданные диагностика тесты сервисов сравнить серверы ухудшение соединения контроль расхода лимиты батарея"
        ClientSettingsNavigation.CONDITIONS,ClientSettingsNavigation.SCHEDULE->"расписание поведение behaviour mcrf в разных сетях профиль mobile data wifi wi-fi ssid маршруты серверы пулы автовыбор авто бс fallback по умолчанию"
        ClientSettingsNavigation.WHITELISTS->"белые списки csqtt 2.5 legacy wdtt qwdtt openflux сервер капча captcha"
        ClientSettingsNavigation.APPEARANCE->"рандомное случайное оформление каждый раз минимализм цвета палитра фон svg узор прозрачность иконка тема светлая темная"
        ClientSettingsNavigation.DEPLOY->"автодеплой развертывание установка сервер vps ssh swg панель 3xui"
        ClientSettingsNavigation.CAPTCHA->"капча captcha авто вручную автоматическая ручная уведомления баннер push пуш vk csqtt wdtt qwdtt"
        ClientSettingsNavigation.OPENFLUX->"openflux белые списки обновление ядро модуль подключение сервер параметры"
        ClientSettingsNavigation.CONNECTIONS->"настройки сервера карандаш в списке подключений"
        ClientSettingsNavigation.BEHAVIOR->"автовосстановление восстанавливать подключение капча captcha уведомления баннер push пуш батарея автозапуск сеть сон постоянный vpn"
        ClientSettingsNavigation.VK->"vk turn vkturn csqtt wdtt qwdtt вк вход аккаунт привязка отвязать автоматические звонки хеши количество потоки wifi wi-fi доверенные сети автопауза выключенный экран cpu процессор отпечаток tls captcha капча"
        ClientSettingsNavigation.DNS->"doh dot udp fakeip резолвер домен блокировка реклама системный bootstrap подключение адреса серверов"
        ClientSettingsNavigation.VPN->"настройки dns днс домены doh dot udp резолвер bootstrap kill switch блокировка трафика нестабильная сеть отказы интервал tun ipv4 совместимость ipv6 mixed listener порт socks http прокси авторизация логин пароль lan wifi hotspot точка доступа адрес телефона раздача частные сети"
        ClientSettingsNavigation.CORE->"sing-box singbox steer основное ядро движок выбор параметры модули"
        ClientSettingsNavigation.SHARING->"lan wifi hotspot раздача адрес пароль socks http"
        ClientSettingsNavigation.TLS->"sni фрагментация fragment tls quic"
        ClientSettingsNavigation.XRAY->"vless reality xhttp mux"
        ClientSettingsNavigation.GEO->"geoip geosite базы страны маршруты списки"
        ClientSettingsNavigation.UPDATES->"версия скачать установить обновить удалить размер движки singbox sing-box steer xray tls csqtt qwdtt wdtt модули основное ядро выбор параметры"
        ClientSettingsNavigation.ADVANCED->"tun mtu tls sni utls fragment фрагментация xray vless mux multiplex quic ядро dns fakeip hosts кэш прокси геобазы geoip geosite базы источник скачать загрузка страны маршруты списки сброс группа"
        else->""
    }
}
