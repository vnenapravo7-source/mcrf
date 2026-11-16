package org.mcrf.client

/** Remaining TUN admission for ordinary packet profiles. No fallback or preference writes.
 * Generic sing-box TUN addresses must stay at their defaults: WG owns its profile addresses. */
object PacketTunSnapshot {
    fun validate(plan:CompactWireGuardPlan.Plan,routing:PacketRoutingSnapshot.Result,tun:ClientTunSettings,core:CoreTuningSettings) {
        validateFields(plan,routing.routes,routing.captured.managed,tun,core)
    }
    fun validatePrepared(plan:CompactWireGuardPlan.Plan,routing:PacketRoutingSnapshot.Prepared,tun:ClientTunSettings,core:CoreTuningSettings) {
        validateFields(plan,routing.captured.projection.tun,routing.captured.managed,tun,core)
    }
    private fun validateFields(plan:CompactWireGuardPlan.Plan,routes:List<String>,managed:PacketManagedDnsPlan.Plan?,tun:ClientTunSettings,core:CoreTuningSettings){
        tun.validate();core.validate()
        require(!tun.ipv4Compatibility||managed?.ipv4Only==true) {"Компактный WG: общая IPv4-совместимость требует управляемого DNS."}
        require(!tun.mixedEnabled&&!tun.httpProxy&&!tun.shareLan&&tun.customInbounds.isBlank()&&tun.dnsPort==0) {
            "Компактный WG работает только как VPN. Локальный прокси, раздача и свои входы пока недоступны; настройки не изменены."
        }
        require(!tun.bypassPrivate&&!tun.bypassDirectIps) {"Компактный WG: дополнительные исключения TUN пока недоступны; используй проверяемые IP-правила."}
        require(tun.address4==ClientTunSettings.DEFAULT_ADDRESS4&&tun.address6==ClientTunSettings.DEFAULT_ADDRESS6) {
            "Компактный WG берёт адреса из WG-профиля; собственные адреса TUN sing-box нельзя молча игнорировать."
        }
        require(tun.mtu==plan.mtu) {"Компактный WG: MTU в настройках TUN должен совпадать с профилем (${plan.mtu})."}
        require(tun.ipv6||plan.addresses.none {':' in it}) {"WG-профиль содержит IPv6: включи IPv6 в TUN или явно измени профиль."}
        require(!core.clashEnabled&&!core.apiEnabled&&!core.ntpEnabled) {"Компактный WG не предоставляет API контроллеров или NTP ядра."}
        require(routes.isNotEmpty()) {"Все IP направлены напрямую: запуск компактного WG не создаст VPN-маршрутов."}
    }
}
