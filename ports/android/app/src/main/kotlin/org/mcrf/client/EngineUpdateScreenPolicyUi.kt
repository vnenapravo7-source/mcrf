package org.mcrf.client

/** Presentation decisions have no Android or package-manager dependencies. */
internal object EngineUpdateScreenPolicy {
    const val BS_LABEL="WL_CSQTT_QWDTT"
    const val BS_HELP="QWDTT и все поколения CSQTT: 2.0, 2.1.5, 2.1.9 и SWG 2.5.1 / 2.5.2 / 2.5.3. WDTT устанавливается отдельно."
    val legacyKinds=setOf("csqtt","csqtt20","csqtt215","csqtt25","qwdtt")
    private val popular=listOf("bs","wdtt","openflux","tgws","xray","tls-core","protocols")
    fun primarySummary(kind:String)=when(kind) {
        "sing-box"->"IPv4 / IPv6 · VPN / прокси.\nРасширенные протоколы; больше размер."
        "steer"->"Компактный · IPv4 / VPN.\nБез локального прокси, TUIC, AnyTLS, NaiveProxy, SSH. WG/AWG — отдельный модуль."
        else->throw IllegalArgumentException("Неизвестный основной движок.")
    }
    fun additionalHelp(kind:String):String=when(kind) {
        "bs"->BS_HELP
        "openflux"->"Движок OpenFlux для обхода белых списков. Поддерживает встроенные и отдельные JS-транспорты."
        "wdtt"->"Отдельный движок WDTT для подключений обхода белых списков, включая поддерживаемые режимы MASQUE."
        "tgws"->"Транспорт Telegram WebSocket для подключений и прокси Telegram."
        "warpscout"->"Поиск и проверка рабочих подключений Cloudflare WARP."
        WgAddonPolicy.KIND->"Самостоятельный VPN-модуль WireGuard и AmneziaWG."
        "xray"->"Xray: VLESS, VMess, Trojan и Shadowsocks, включая транспорты REALITY и XHTTP."
        "tls-core"->"Дробление TLS-пакетов и изменение регистра SNI для TCP TLS-подключений. Настраивается в параметрах TLS."
        "protocols"->"TUIC, AnyTLS, NaiveProxy и SSH для компактного основного движка Steer."
        else->""
    }

    /** Preserve the previous catalogue order within the remaining entries. */
    fun additional(kinds:Iterable<String>):List<String> {
        val available=kinds.distinct()
        return popular.filter {it in available}+available.filterNot {it in popular}
    }
    enum class Migration { NONE, INSTALL, REMOVE }
    fun migration(legacy:Collection<String>,replacementVerified:Boolean)=when {
        legacy.none {it in legacyKinds}->Migration.NONE
        !replacementVerified->Migration.INSTALL
        else->Migration.REMOVE
    }
    /** An unreadable catalogue never grants permission to decline migration. */
    fun canDecline(serverKinds:Collection<String>?):Boolean =
        serverKinds!=null&&serverKinds.none {it in legacyKinds}
}

/** Memory only: a new process gets a fresh check, Activity recreation does not. */
internal class EngineUpdateProcessLaunch {
    private var started=false
    @Synchronized fun start(check:()->Boolean):Boolean {
        if(started)return true
        started=check()
        return started
    }
}
