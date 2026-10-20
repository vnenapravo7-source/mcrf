package org.mcrf.client

/** Presentation preferences, not engine permissions or a replacement routing policy. */
object UsageFeatures {
    const val KEY="usageFeatures"
    const val ASKED="usageFeaturesAsked"
    enum class Feature(val id:String,val title:String,val detail:String) {
        VPN("vpn","VPN","Трафик приложений через выбранный VPN-сервер"),
        PROXY("proxy","Прокси","Локальный HTTP/SOCKS-прокси и раздача другим устройствам"),
        WARP("warp","WARP","Создание и подбор подключения Cloudflare WARP"),
        WHITELIST("whitelist","Turn протоколы для БС","CSQTT, QWDTT, WDTT и звонки VK"),
        OPENFLUX("openflux","OpenFlux","Отдельный транспорт белых списков"),
        S3_BYPASS("s3-bypass","S3 Bypass","Белые списки через S3-хранилище")
    }
    val all:Set<Feature> = Feature.values().toSet()
    fun decode(raw:String?):Set<Feature> {
        if(raw==null)return all // Existing installations retain their entire interface.
        val ids=raw.split(',').toSet()
        return Feature.values().filterTo(linkedSetOf()){it.id in ids}.apply {
            // Old whitelist selection included OpenFlux; migrate once in memory.
            if("v2" !in ids&&Feature.WHITELIST in this)add(Feature.OPENFLUX)
        }.ifEmpty {all}
    }
    fun encode(features:Set<Feature>):String {
        require(features.isNotEmpty()) {"Выбери хотя бы одну возможность."}
        return "v2,"+Feature.values().filter {it in features}.joinToString(","){it.id}
    }
    fun visible(page:String,features:Set<Feature>):Boolean = when(page) {
        "Главная" -> Feature.VPN in features||Feature.PROXY in features||Feature.WARP in features
        "Автодеплой" -> Feature.VPN in features||whitelists(features)
        ClientSettingsNavigation.WHITELISTS -> whitelists(features)
        ClientSettingsNavigation.CAPTCHA -> Feature.WHITELIST in features
        ClientSettingsNavigation.OPENFLUX -> Feature.OPENFLUX in features
        "WARP" -> Feature.WARP in features
        ClientSettingsNavigation.VPN -> usesVpn(features)||Feature.PROXY in features
        ClientSettingsNavigation.VK -> Feature.WHITELIST in features
        ClientSettingsNavigation.TLS,ClientSettingsNavigation.XRAY -> Feature.VPN in features||Feature.PROXY in features
        ClientSettingsNavigation.SHARING -> Feature.PROXY in features
        else -> true
    }
    fun usesVpn(features:Set<Feature>)=features.any {it!=Feature.PROXY}
    fun whitelists(features:Set<Feature>)=features.any {it in setOf(Feature.WHITELIST,Feature.OPENFLUX,Feature.S3_BYPASS)}
    fun connection(entry:ServerImport.Server,features:Set<Feature>)=when(entry.protocol){
        "openflux"->Feature.OPENFLUX in features
        "s3-bypass",S3BypassProfile.KIND->Feature.S3_BYPASS in features
        "csqtt","qwdtt","wdtt","olcrtc","dnstt"->Feature.WHITELIST in features
        else->true
    }
    fun showModeSwitch(features:Set<Feature>)=Feature.PROXY in features&&usesVpn(features)
    fun showFakeIp(features:Set<Feature>,configured:Boolean=false)=usesVpn(features)||configured
    fun routeModes(features:Set<Feature>,configured:String):List<String> =
        listOf("any","addresses","apps","in-apps","except-apps").filter {
            usesVpn(features)||it in setOf("any","addresses")||it==configured
        }
    fun showRouteApps(features:Set<Feature>,configured:Boolean=false)=usesVpn(features)||configured
    fun showGlobalAppPolicies(features:Set<Feature>,configured:Boolean=false)=usesVpn(features)||configured
    fun routedTrafficLabel(features:Set<Feature>)=if(usesVpn(features))"VPN" else "Прокси"
    fun showAndroidHttpProxy(features:Set<Feature>,configured:Boolean=false)=Feature.PROXY in features&&usesVpn(features)||configured
    fun protocol(name:String,engine:String,features:Set<Feature>)=when {
        engine=="sing-box-lx"->Feature.VPN in features||Feature.PROXY in features||
            Feature.WARP in features&&name=="WireGuard / AmneziaWG"
        name.equals("OpenFlux",true)->Feature.OPENFLUX in features
        name.equals("S3 Bypass",true)->Feature.S3_BYPASS in features
        name=="Telegram WS"->telegram(features)
        else->Feature.WHITELIST in features
    }
    fun telegram(features:Set<Feature>,wanted:Boolean=false,running:Boolean=false)=Feature.PROXY in features||Feature.WHITELIST in features||wanted||running
    fun preferredVpn(features:Set<Feature>,stored:Boolean)=when {
        Feature.PROXY !in features->true
        !usesVpn(features)->false
        else->stored
    }
    fun deployRecipe(id:String,features:Set<Feature>)=when(id){
        "3xui"->Feature.VPN in features
        "swg"->Feature.WHITELIST in features
        "openflux"->Feature.OPENFLUX in features
        "s3-bypass",S3BypassProfile.KIND->Feature.S3_BYPASS in features
        else->false
    }
    fun engine(kind:String,features:Set<Feature>)=when(kind){
        "wg-awg"->usesVpn(features)
        "warpscout"->Feature.WARP in features
        "tgws"->telegram(features)
        "openflux"->Feature.OPENFLUX in features
        "s3-bypass",S3BypassProfile.KIND->Feature.S3_BYPASS in features
        "csqtt","csqtt25","qwdtt","wdtt","olcrtc","dnstt"->Feature.WHITELIST in features
        "xray","tls-core"->Feature.VPN in features||Feature.PROXY in features
        else->true
    }
    fun needsFirstChoice(raw:String?,asked:Boolean,fresh:Boolean)=raw==null&&!asked&&fresh
}
