package org.mcrf.client

import android.content.Context
import android.net.Network
import org.json.JSONObject

/** Compiles the same saved route/DNS/TUN settings without starting a carrier or
 * primary engine. The immutable result is admitted by BS before handover. */
internal class BsNativeProfilePreparation private constructor(
    val captured:BsNativeRoutingSnapshot.Captured,
    private val compiledText:String,
    val localPort:Int,
    val storageGeneration:String,
    private val routingText:String?
) {
    fun compiled()=JSONObject(compiledText)
    fun requireCurrent(context:Context){check(ProfileStore.generation(context)==storageGeneration){"Настройки изменились во время подготовки БС."}}
    fun routing()=routingText?.let(::JSONObject)
    override fun toString()="BsNativeProfilePreparation(redacted)"
    companion object {
        fun capture(context:Context,template:JSONObject,candidate:JSONObject?,tunnelTag:String,port:Int,
                    applyRoutes:Boolean,physical:Network,routingState:JSONObject?,resolvedCarrier:JSONObject?=null,
                    egresses:List<BsNativeRoutingSnapshot.Egress> = emptyList(),mainEgressId:String?=null,
                    admitRouteServers:((List<ServerImport.Server>)->List<BsNativeRoutingSnapshot.Egress>)?=null,
                    vpn:Boolean=true,
                    cancelled:()->Boolean):BsNativeProfilePreparation {
            fun current(){check(!cancelled()){ "Подготовка БС отменена." }}
            current()
            val storageGeneration=ProfileStore.generation(context)
            val source=JSONObject((candidate?.optJSONObject("source")?:template).toString())
            if(candidate?.has("source")==true)template.optJSONObject("dns")?.let {source.put("dns",JSONObject(it.toString()))}
            require(source.optJSONObject("mcrf_carrier")?.optString("outbound_tag")==tunnelTag){ "БС: подготовка должна относиться к выбранному подключению." }
            val carrier=resolvedCarrier?:source.getJSONObject("mcrf_carrier")
            scopeGeneratedCarrierDns(source,tunnelTag)
            source.remove("mcrf_carrier");source.remove("mcrf_bs")
            var compiled=ProfileConfig.build(source.toString(),vpn,port)
            LocalProxyConfig.apply(compiled,LocalProxySettings.value(context))
            var capturedRouting:JSONObject?=null
            var routeServers=emptyList<ServerImport.Server>()
            if(applyRoutes){
                val catalogue=Connections(context).apply {load()}
                val stored=JSONObject(requireNotNull(routingState){"БС: нужен точный снимок маршрутизации текущего профиля."}.toString())
                val state=RoutingConnectionBinding.prepare(context,stored,catalogue.entries(),true,catalogue.probes())
                routeServers=RouteServerPlan(state,catalogue.entries()).entries
                current()
                compiled=RouteRules.apply(compiled,state,vpn,PresetAssets.resolve(context,state),PresetAssets.ipRuleSetTags(context,state),DirectDnsNetwork.ipv4Only(context,physical),catalogue.entries(),nativeBs=true)
                capturedRouting=JSONObject(state.toString())
            }else require(egresses.map {it.serverId}==listOfNotNull(mainEgressId))
            val tuning=CoreTuningStore(context).load()
            val optional=OptionalCorePlan(compiled,OptionalCoreStore(context).load(),tuning)
            require(!optional.requiresWorkers){"БС: подготовленный профиль требует дополнительного протокольного движка."}
            compiled=CoreTuningConfig.apply(compiled,tuning)
            val tun=ClientTunStore(context)
            val configured=ClientTunConfig.apply(compiled,tun.load(),tun.auth(),port)
            val mainKind=EngineModules.carrierKind(carrier)
            compiled=if(routeServers.isEmpty())CarrierAddressFamily.apply(configured.config,mainKind)else {
                val restricted=routeServers.filter {entry->CarrierAddressFamily.needsIpv4(entry.carrier?.let(EngineModules::carrierKind))}
                    .mapTo(linkedSetOf()) {it.id}
                RouteServerAddressFamily.apply(configured.config,tunnelTag,CarrierAddressFamily.needsIpv4(mainKind),restricted)
            }
            current()
            val bound=egresses.map {it.serverId}.toSet()
            val unresolved=routeServers.filter {it.id !in bound}
            val additional=if(unresolved.isEmpty())emptyList()else requireNotNull(admitRouteServers) {
                "БС: для дополнительных серверов правил нужны автономные транспорты."
            }(unresolved)
            val allEgresses=egresses+additional
            require(routeServers.map {it.id}.toSet()+listOfNotNull(mainEgressId)==allEgresses.map {it.serverId}.toSet()){
                "БС: состав выходов изменился во время подготовки."
            }
            val captured=BsNativeRuleSetFiles.capture(context,compiled,tunnelTag,allEgresses,mainEgressId,cancelled)
            current()
            check(ProfileStore.generation(context)==storageGeneration){"Настройки изменились во время подготовки БС."}
            return BsNativeProfilePreparation(captured,compiled.toString(),configured.localPort?:0,storageGeneration,capturedRouting?.toString())
        }

        /** Recognize the exact two built-in rules and local placeholder emitted
         * by ServerImport for a carrier. The native TUN has its own DNS alias. */
        internal fun scopeGeneratedCarrierDns(source:JSONObject,tunnelTag:String) {
            val route=source.optJSONObject("route")?:return
            val outputs=source.optJSONArray("outbounds")?:return
            val rules=route.optJSONArray("rules")?:return
            if(route.optString("final")!=tunnelTag||route.keys().asSequence().toSet()!=setOf("final","default_domain_resolver","rules")||
                route.optString("default_domain_resolver") !in setOf("chosen",DnsConfig.APP_DNS_TAG)||rules.length()!=2||outputs.length()!=2)return
            val direct=outputs.optJSONObject(0)?:return
            val placeholder=outputs.optJSONObject(1)?:return
            if(direct.keys().asSequence().toSet()!=setOf("type","tag")||direct.optString("type")!="direct"||direct.optString("tag")!="direct"||
                placeholder.optString("tag")!=tunnelTag||placeholder.optString("type")!="socks"||
                placeholder.optString("server")!="127.0.0.1"||placeholder.optInt("server_port")!=1||placeholder.optString("version")!="5")return
            val sniff=rules.optJSONObject(0)?:return
            val dns=rules.optJSONObject(1)?:return
            if(sniff.length()!=1||sniff.optString("action")!="sniff"||dns.length()!=2||
                dns.optString("action")!="hijack-dns"||dns.optString("protocol")!="dns")return
            rules.put(1,RouteRules.defaultDnsHijack(nativeBs=true))
        }
    }
}
