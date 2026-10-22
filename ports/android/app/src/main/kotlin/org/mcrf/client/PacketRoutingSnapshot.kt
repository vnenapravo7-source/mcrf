package org.mcrf.client

import org.json.JSONObject

/** Routing/DNS admission only. Caller still owes complete TUN/core/native admission.
 * Never enables the backend or rewrites persisted settings to make them compatible. */
object PacketRoutingSnapshot {
    /** Pure preparation is deliberately NOT an admitted runtime snapshot. In particular,
     * captured BLOCK projections must still pass the shared host/worker capability gate. */
    class Prepared internal constructor(val captured:PacketRouteEnvelope.Captured,dns:List<String>,directApps:List<String>) {
        val dns:List<String> = java.util.Collections.unmodifiableList(dns.toList())
        val directApps:List<String> = java.util.Collections.unmodifiableList(directApps.toList())
        override fun toString()="PacketRoutingPrepared(redacted)"
    }
    class Result internal constructor(val captured:PacketRouteEnvelope.Captured,dns:List<String>,directApps:List<String>) {
        val routes:List<String> = captured.projection.tun
        val dns:List<String> = java.util.Collections.unmodifiableList(dns.toList())
        val directApps:List<String> = java.util.Collections.unmodifiableList(directApps.toList())
    }
    fun capture(state:JSONObject,plan:CompactWireGuardPlan.Plan,admission:PacketRouteEnvelope.Admission=PacketRouteEnvelope.Admission.LEGACY_IP,managedDnsAdmission:Boolean=false,ipv4Only:Boolean=false):Result {
        val prepared=prepare(state,plan,ipv4Only)
        // Production admission remains CLOSED for captured drop until the complete
        // native leak/drop/permitted-after-block gate is accepted, on BOTH ends.
        PacketRouteEnvelope.workerPlan(plan,prepared.captured.settings,admission,managedDnsAdmission)
        return Result(prepared.captured,prepared.dns,prepared.directApps)
    }
    fun prepare(state:JSONObject,plan:CompactWireGuardPlan.Plan,ipv4Only:Boolean=false):Prepared {
        RoutingStore.validate(state)
        RouteServerPlan.requireSingleServerCore(state)
        require(state.getString("defaultTarget")=="proxy") {"Компактный WG: выход по умолчанию должен быть VPN."}
        require(state.optString("appMode","all")=="all") {"Компактный WG: режим «только выбранные приложения» пока недоступен."}
        require((state.optJSONArray("vpnApps")?.length()?:0)==0) {"Компактный WG: принудительный VPN отдельных приложений пока недоступен."}
        require(!RemoteRoutingConfig.value(state).optBoolean("adblock")) {"Компактный WG: встроенный DNS-фильтр рекламы пока недоступен."}
        RemoteRoutingConfig.value(state).optJSONArray("profiles")?.let {a->
            require((0 until a.length()).none {a.getJSONObject(it).optBoolean("enabled",true)}) {"Компактный WG: импортированные наборы маршрутов пока требуют sing-box."}
        }
        require(GeoDataConfig.neededSelectors(state).isEmpty()) {"Компактный WG: GeoIP/GeoSite пока требуют sing-box."}
        val ipRules=mutableListOf<PacketIpRoutes.Rule>()
        val conditions=PacketIpRoutes.Conditions()
        val appRules=mutableListOf<Pair<List<String>,PacketIpRoutes.Target>>()
        val rules=GeoDataConfig.orderedRules(state)
        repeat(rules.length()) {index->val rule=rules.getJSONObject(index)
            if(!rule.optBoolean("enabled",true))return@repeat
            require(!rule.has("invert")||rule.opt("invert") is Boolean){"Компактный WG: инверсия должна быть логическим значением."}
            require(PresetRules.ids(rule).isEmpty()&&!rule.has("dns")) {
                "Компактный WG: готовые/геосписки и отдельный DNS правила пока недоступны."
            }
            val target=when(rule.optString("target","proxy")){"direct"->PacketIpRoutes.Target.DIRECT;"proxy"->PacketIpRoutes.Target.VPN;else->PacketIpRoutes.Target.BLOCK}
            val mode=rule.optString("mode","any")
            require(mode in setOf("any","apps","addresses")) {"Компактный WG: совместные условия по адресу и приложению пока недоступны."}
            if(rule.has("nativeMatch")){
                val match=rule.optJSONObject("nativeMatch")
                require(match!=null&&mode=="addresses"&&rule.getString("text").isBlank()){
                    "Компактный WG: точное IP-условие нельзя смешивать с текстом или условиями приложения."
                }
                val cidrs=conditions.compile(match,rule.optBoolean("invert",false))
                if(cidrs.isNotEmpty())ipRules+=PacketIpRoutes.Rule(cidrs,target)
                return@repeat
            }
            val groups=RouteRules.parse(rule.getString("text"))
            require(!rule.optBoolean("invert")||mode!="apps"&&groups.getValue("package_name").isEmpty()&&
                groups.getValue("process_name").isEmpty()&&groups.getValue("process_path").isEmpty()&&groups.getValue("ip_cidr").isNotEmpty()){
                "Компактный WG: инверсия доступна только для полного IP-условия, не приложения."
            }
            if(mode!="apps") {
                require(groups.getValue("domain").isEmpty()&&groups.getValue("domain_suffix").isEmpty()) {"Компактный WG: доменные правила пока требуют sing-box."}
                groups.getValue("ip_cidr").takeIf {it.isNotEmpty()}?.let {
                    val cidrs=conditions.compile(JSONObject().put("ip_cidr",org.json.JSONArray(it)),rule.optBoolean("invert",false))
                    if(cidrs.isNotEmpty())ipRules+=PacketIpRoutes.Rule(cidrs,target)
                }
            }
            if(mode!="addresses") {
                require(groups.getValue("process_name").isEmpty()&&groups.getValue("process_path").isEmpty()) {"Компактный WG: нужны пакеты Android, не процессы Windows."}
                require(target!=PacketIpRoutes.Target.BLOCK||groups.getValue("package_name").isEmpty()) {"Компактный WG: блокировка приложения требует проверки владельца каждого потока, не исключения из VPN."}
                groups.getValue("package_name").takeIf {it.isNotEmpty()}?.let {appRules+=it to target}
            }
        }
        require(ipRules.isEmpty()||appRules.isEmpty()) {"Компактный WG: смешанные правила по IP и приложениям пока требуют sing-box."}
        val direct=linkedSetOf<String>()
        state.getJSONArray("excludedApps").let {a->repeat(a.length()){direct+=a.getString(it)}}
        val claimed=hashSetOf<String>()
        for((apps,target) in appRules)for(app in apps)if(claimed.add(app)&&target==PacketIpRoutes.Target.DIRECT)direct+=app
        val captured=PacketRouteEnvelope.capture(plan.routes,ipRules)
        if(PacketManagedDnsPlan.needed(state,plan,ipv4Only)){
            val dns=PacketManagedDnsPlan.prepare(state,plan,ipv4Only)
            val managed=PacketRouteEnvelope.withManagedDns(captured,dns,PacketManagedDnsPlan.aliases(plan))
            PacketManagedDnsPlan.validate(dns,plan,managed.projection)
            return Prepared(managed,requireNotNull(managed.dns),direct.toList())
        }
        val dns=if(plan.dns.isNotEmpty())plan.dns else {
            val server=DnsConfig.build(state.getJSONObject("globalDns"),"chosen","bootstrap")
            require(server.optString("type")=="udp"&&server.optInt("server_port",53)==53&&RouteRules.literal(server.optString("server"))!=null) {
                "Компактный WG: укажи DNS в профиле или числовой UDP DNS на порту 53; зашифрованный DNS пока недоступен."
            }
            listOf(server.getString("server"))
        }
        PacketDnsSnapshot.validate(plan,captured.projection,dns)
        return Prepared(PacketRouteEnvelope.withDns(captured,dns),dns,direct.toList())
    }
}
