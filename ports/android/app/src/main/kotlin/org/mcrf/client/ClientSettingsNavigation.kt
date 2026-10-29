package org.mcrf.client

/** Stable navigation identities; editors keep their own autosave and validation. */
object ClientSettingsNavigation {
    const val ROOT = "Настройки"
    const val INVITE = "Инвайт-код"
    const val APPEARANCE = "Оформление"
    const val MINIMALISM = "Минимализм"
    const val DNS = "DNS и домены"
    const val BEHAVIOR = "Подключение и работа в фоне"
    const val DEPLOY = "Автодеплой"
    const val WHITELISTS = "Белые списки"
    const val CAPTCHA = "Капча VK"
    const val CONNECTIONS = "Настройки серверов"
    const val OPENFLUX = "Настройки OpenFlux"
    const val VPN = "VPN и прокси"
    const val SHARING = "Раздача VPN другим устройствам"
    const val CORE = "Настройки основного ядра"
    const val TLS = "Дополнительные настройки TLS"
    const val XRAY = "Дополнительное ядро Xray"
    const val GEO = "Базы GeoIP и GeoSite"
    const val VK = "Аккаунт и звонки VK"
    const val UPDATES = "Обновления и движки"
    const val ADVANCED = "Расширенные настройки"
    val rootLeading = listOf(INVITE, APPEARANCE, DNS, DEPLOY)
    // Retain the historical home identity for saved navigation and deep links.
    fun tabTitle(page:String)=if(page=="Главная")"VPN" else if(page==WHITELISTS)"БС" else if(page=="Маршрутизация")"Маршруты" else if(page==VK)"Vk Turn" else if(page==MINIMALISM)"Скрыть разделы" else page
    val mainTabs = listOf("Главная", WHITELISTS, "Маршрутизация", "Логи")
    val children: Set<String> = linkedSetOf(INVITE, MINIMALISM, APPEARANCE, DNS, DEPLOY, BEHAVIOR, OPENFLUX, VPN, GEO, VK, UPDATES, ADVANCED)
    fun canonical(page:String):String=when(page){
        "VPN и локальный прокси",SHARING->VPN
        "Обновления"->UPDATES
        CORE->UPDATES
        CAPTCHA,CONNECTIONS->"Главная"
        TLS,XRAY->ADVANCED
        else->page
    }
    fun isSettings(page: String) = page == ROOT || page == CORE || page in children
    fun back(page: String, origin: String, directEntry:Boolean=false,advancedFrom:String?=null): String = when {
        page==ADVANCED&&!advancedFrom.isNullOrBlank()&&advancedFrom!=ADVANCED -> canonical(advancedFrom)
        page == CAPTCHA || page == CONNECTIONS -> origin.takeUnless(::isSettings)?:"Главная"
        page == MINIMALISM -> APPEARANCE
        directEntry && (page in children || page == CORE) -> origin.takeUnless(::isSettings) ?: "Главная"
        page in children || page == CORE -> ROOT
        page == ROOT -> origin.takeUnless(::isSettings) ?: "Главная"
        else -> "Главная"
    }
}
