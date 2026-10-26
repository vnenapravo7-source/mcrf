package org.mcrf.client

import android.widget.LinearLayout

class ClientTunUi(private val activity: MainActivity,private val features:Set<UsageFeatures.Feature> = UsageFeaturesUi.selected(activity)) {
    companion object { const val PAGE_TITLE = "VPN и прокси" }
    /** Host navigation supplies this view as a page, without a second settings dialog. */
    fun pageView(): LinearLayout = view()
    fun view(): LinearLayout = build(false)
    fun advancedView(): LinearLayout = build(true)
    private fun build(advanced:Boolean): LinearLayout {
        val store = ClientTunStore(activity)
        val settings=store.load()
        val form = CoreTuningForm(activity,settings.json()) { store.save(ClientTunSettings.fromJson(it)) }
            .preserving {store.load().json()}
        if(advanced)form.grouped(ClientTunSettings().json()){store.load().json()}
        val showVpn=UsageFeatures.usesVpn(features)
        val showProxy=UsageFeatures.Feature.PROXY in features
        if(!advanced) {
        form.view.addView(BsDetectionSitesUi.view(activity),0,activity.space(0,20))
        form.toggle("ipv4Compatibility","Совместимость IPv4","Для всех существующих и новых серверов: разрешает доменные имена только в IPv4 при следующем подключении. Профили и IPv6-защита VPN не изменяются. Сайты только с IPv6 могут не открыться; явные и ранее закэшированные IPv6-адреса не преобразуются в IPv4.")
        if(showVpn) {
        form.section("Виртуальный сетевой интерфейс · TUN")
        form.toggle("ipv6","IPv6 в VPN","Добавляет IPv6-адрес и маршруты в VPN-туннель. Не отменяет «Совместимость IPv4»: она управляет разрешением имён, а этот переключатель — охватом IPv6-трафика. При выключении IPv6 не должен обходить VPN.")
        form.section("Обход локальных сетей")
        form.toggle("bypassPrivate","Локальные сети напрямую","Доступ к роутеру и устройствам локальной сети без VPN. Пользовательские и серверные правила сохраняют приоритет. Список сетей можно изменить в расширенных настройках.")
        form.action("Список локальных сетей","Открыть собственные диапазоны в расширенных настройках."){activity.openAdvanced("setting.privateRanges")}
        }
        if(showProxy) {
        form.section("Прокси на телефоне")
        form.hint("Один локальный порт для HTTP и SOCKS5. Трафик идёт через выбранное подключение MCRF.")
        form.toggle("mixedEnabled","Включить локальный прокси","Для программ на этом телефоне: 127.0.0.1.")
        form.field("mixedPort","Фиксированный локальный порт","Используется при выключенном случайном порте; 1024–65535.",true)
        form.view.addView(activity.proxyLinks(),activity.space(0,8))
        form.view.addView(LanSharingUi(activity).build(),activity.space(0,12))
        }
        form.action("Расширенные настройки","Дополнительные локальные прокси в формате JSON и параметры TUN.") {activity.openAdvanced(if(showProxy)"setting.customInbounds"else"setting.privateRanges")}
        } else {
        if(showProxy) {
        form.section("DNS для других программ")
        form.field("dnsPort","Порт DNS для других программ","Только если другой программе нужен DNS внутри MCRF на этом телефоне. Это не выбор DNS-сервера VPN. 0 — выключен; иначе порт 1024–65535 на 127.0.0.1, UDP/TCP.",true)
        }
        if(showVpn) {
        form.section("Локальная сеть · собственные диапазоны")
        form.field("privateRanges","Сети для прямого доступа","Меняйте только для нестандартной домашней или рабочей сети. По одной сети с префиксом на строку, например 192.168.0.0/16. Применяется при включённом «Локальные сети напрямую»; правила маршрутизации сохраняют приоритет.")
        form.section("Размер пакета и внутренние адреса VPN · TUN")
        form.hint("Обычно менять не нужно. Помогает при конфликте адресов с другой сетью или проблемах передачи больших пакетов. Это внутренние адреса VPN, не адрес удалённого сервера.")
        form.field("mtu","Размер пакета VPN · MTU","Меньше — больше служебных расходов, больше — не каждая сеть пропустит пакет. Обычно оставьте штатное значение; при IPv6 минимум 1280.",true)
        form.field("address4","Внутренний IPv4-адрес VPN","Адрес интерфейса с размером сети, например 172.19.0.1/30. Меняйте только при конфликте подсетей.")
        form.field("address6","Внутренний IPv6-адрес VPN","Адрес с префиксом CIDR. Используется только при включённом IPv6.")
        }
        if(showProxy) {
        form.section("Особые способы использования локального прокси")
        form.toggle("randomPort","Менять локальный порт при каждом запуске","Выбирается свободный порт вместо фиксированного. Программы, которым вы указали старый порт вручную, потребуется перенастроить.")
        }
        if(UsageFeatures.showAndroidHttpProxy(features,settings.httpProxy)) {
            form.section("Системный HTTP-прокси Android · VPN")
            if(!showVpn)form.hint("Эта настройка сохранена из режима VPN. Если подключение отклоняется из-за системного прокси, выключи её здесь. Сохранённое значение не сбрасывается автоматически.")
            form.toggle("httpProxy","Сообщать Android о локальном HTTP-прокси","Для программ, учитывающих системную настройку прокси VPN. Работает только без авторизации: Android не передаёт логин и пароль. Обычному подключению MCRF это не нужно.")
            form.field("httpBypass","Не использовать системный HTTP-прокси для доменов","По одному домену или маске *.example.com на строку. Влияет только на настройку HTTP-прокси Android, а не заменяет правила маршрутизации MCRF.")
        }
        if(showProxy) {
        form.section("Дополнительные входы для приложений телефона")
        form.field("customInbounds","Дополнительные локальные прокси · JSON","Нужны, только если программе требуется отдельный локальный порт. Для обычного использования достаточно готового HTTP/SOCKS-прокси выше — оставьте поле пустым. Это не новый удалённый сервер и не раздача по Wi-Fi. Формат: массив входов mixed, socks, http или direct; у каждого уникальные tag и порт, адрес только loopback (127.0.0.1/::1). Ошибочная конфигурация не позволит подключиться: ядро проверяет её перед запуском.")
        }
        }
        form.finishGroups()
        return form.view
    }
}
