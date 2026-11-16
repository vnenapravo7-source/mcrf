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
        form.view.addView(ClientBehaviorUi.killSwitch(activity),0,activity.space(0,12))
        form.view.addView(BsDetectionSitesUi.view(activity),0,activity.space(0,20))
        form.view.addView(SettingsUiNodes(activity).actionRow(ClientSettingsNavigation.DNS,
            "DNS для VPN и прямых маршрутов","arrow","Открыть: ${ClientSettingsNavigation.DNS}") {
            activity.navigate(ClientSettingsNavigation.DNS)
        }.apply {tag="vpn-dns-settings"},1,activity.space(0,12))
        form.toggle("ipv4Compatibility","Совместимость IPv4","При новом подключении ищет только IPv4-адреса доменов. Явные и закэшированные IPv6-адреса сохраняются. Сайты только с IPv6 могут не открыться.")
        if(showVpn) {
        form.section("Виртуальный сетевой интерфейс · TUN")
        form.toggle("ipv6","IPv6 в VPN","Направляет IPv6-трафик в туннель. При выключении он блокируется, чтобы не обходить VPN. «Совместимость IPv4» отдельно управляет поиском адресов.")
        form.section("Обход локальных сетей")
        form.toggle("bypassPrivate","Локальные сети напрямую","Роутер и устройства домашней сети доступны без VPN. Пользовательские и серверные правила важнее; диапазоны меняются ниже.")
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
        form.field("dnsPort","Порт DNS для других программ","Локальный DNS на 127.0.0.1, UDP/TCP. 0 — выключен; порт 1024–65535. Удалённый DNS-сервер VPN выбирается в разделе DNS.",true)
        }
        if(showVpn) {
        form.section("Локальная сеть · собственные диапазоны")
        form.field("privateRanges","Сети для прямого доступа","По одной сети на строку, например 192.168.0.0/16. Действует при включённом «Локальные сети напрямую»; правила маршрутизации важнее.")
        form.section("Размер пакета и внутренние адреса VPN · TUN")
        form.hint("Меняйте при конфликте подсетей или проблемах с большими пакетами. Здесь внутренние адреса VPN; обычно подходят значения по умолчанию.")
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
            form.toggle("httpProxy","Сообщать Android о локальном HTTP-прокси","Для приложений, учитывающих HTTP-прокси VPN. Android не передаёт логин и пароль: нужна работа без авторизации. Обычно не требуется.")
            form.field("httpBypass","Не использовать системный HTTP-прокси для доменов","Домен или *.example.com, по одному на строку. Исключение только для HTTP-прокси Android; маршруты MCRF задаются отдельно.")
        }
        if(showProxy) {
        form.section("Дополнительные входы для приложений телефона")
        form.field("customInbounds","Дополнительные локальные прокси · JSON","Для отдельных портов программ на телефоне. Обычно оставьте пустым.\n\nМассив mixed, socks, http или direct: уникальные tag и порт, адрес 127.0.0.1/::1. Ошибка JSON не даст запустить подключение.")
        }
        }
        form.finishGroups()
        return form.view
    }
}
