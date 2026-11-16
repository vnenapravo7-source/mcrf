package org.mcrf.client

/** Stable navigation identities; editors keep their own autosave and validation. */
object ClientSettingsNavigation {
    const val ROOT = "Настройки"
    const val INVITE = "Инвайт-код"
    const val BETA = "Beta-функции"
    const val ANUBIS = "Anubis - автовключение VPN · Beta"
    const val DIAGNOSTICS = "Диагностика подключения"
    const val APPEARANCE = "Оформление"
    const val MINIMALISM = "Минимализм"
    const val DNS = "Настройки DNS"
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
    const val EXPERIENCE = "Режим интерфейса"
    const val CONDITIONS = ConditionProfiles.TITLE
    const val ROUTES = "Маршрутизация"
    const val SCHEDULE = "Расписание"
    const val ROUTES_PAGE_KEY = "routesLastPage"
    val rootLeading = listOf(BETA, APPEARANCE, DEPLOY)
    // Retain the historical home identity for saved navigation and deep links.
    fun tabTitle(page:String)=if(page=="Главная")"VPN" else if(page==WHITELISTS)"БС" else if(page=="Маршрутизация")"Маршруты" else if(page==VK)"Vk Turn" else if(page==ANUBIS)"Anubis · Beta" else if(page==MINIMALISM)"Скрыть разделы" else page
    val mainTabs = listOf("Главная", WHITELISTS, "Маршрутизация", "Логи")
    val children: Set<String> = linkedSetOf(INVITE, BETA, ANUBIS, DIAGNOSTICS, MINIMALISM, APPEARANCE, DNS, DEPLOY, BEHAVIOR, OPENFLUX, VPN, GEO, VK, UPDATES, ADVANCED, EXPERIENCE)
    private val simplePages=setOf(ROOT,EXPERIENCE,INVITE,APPEARANCE,DEPLOY,BEHAVIOR,UPDATES,VK,OPENFLUX,"Главная",WHITELISTS,ROUTES,SCHEDULE,CONDITIONS)
    fun visible(page:String,mode:ExperienceMode)=mode==ExperienceMode.EXPERT||page in simplePages
    fun experimental(page:String)=page==BETA
    fun isRoutes(page:String)=canonical(page).let {it==ROUTES||it==SCHEDULE}
    fun routePage(saved:String?)=if(canonical(saved.orEmpty())==SCHEDULE)SCHEDULE else ROUTES
    fun bottomTarget(tab:String,savedRoutePage:String?)=if(tab==ROUTES)routePage(savedRoutePage)else tab
    fun mainTab(page:String)=if(isRoutes(page))ROUTES else canonical(page)
    fun canonical(page:String):String=when(page){
        CONDITIONS,"Behaviour MCRF","Поведение MCRF"->SCHEDULE
        EXPERIENCE->APPEARANCE
        OPENFLUX->WHITELISTS
        "DNS и домены"->DNS
        GEO->ADVANCED
        "VPN и локальный прокси",SHARING->VPN
        "Обновления"->UPDATES
        CORE->UPDATES
        CAPTCHA,CONNECTIONS->"Главная"
        TLS,XRAY->ADVANCED
        else->page
    }
    fun settingsTabTarget(current:String,last:String)=if(isSettings(current))ROOT else last.takeIf(::isSettings)?:ROOT
    fun isSettings(page: String) = page == ROOT || page == CORE || page in children
    fun back(page: String, origin: String, directEntry:Boolean=false,advancedFrom:String?=null): String = when {
        page==ADVANCED&&!advancedFrom.isNullOrBlank()&&canonical(advancedFrom)!=ADVANCED -> canonical(advancedFrom)
        page == CAPTCHA || page == CONNECTIONS -> origin.takeUnless(::isSettings)?:"Главная"
        page == MINIMALISM -> APPEARANCE
        page == DIAGNOSTICS || page == ANUBIS -> BETA
        page == DNS -> VPN
        page == GEO -> ADVANCED
        directEntry && (page in children || page == CORE) -> origin.takeUnless(::isSettings) ?: "Главная"
        page == EXPERIENCE -> ROOT
        page in children || page == CORE -> ROOT
        page == ROOT -> origin.takeUnless(::isSettings) ?: "Главная"
        else -> "Главная"
    }
}
