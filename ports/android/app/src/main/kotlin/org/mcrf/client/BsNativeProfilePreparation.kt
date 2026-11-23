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
                    standaloneMain:ServerImport.Server?=null,
                    admitMixedRoutes:((MixedNativeRoutePlan,JSONObject,Set<String>)->List<BsNativeRoutingSnapshot.Egress>)?=null,
                    ownedFixture:BsNativePhysicalServiceFixture?=null,
                    serverRoutingMode:String="mcrf",
                    cancelled:()->Boolean):BsNativeProfilePreparation {
            fun current(){check(!cancelled()){ "Подготовка БС отменена." }}
            current()
            val storageGeneration=ProfileStore.generation(context)
            val source=JSONObject((candidate?.optJSONObject("source")?:template).toString())
            if(candidate?.has("source")==true)template.optJSONObject("dns")?.let {source.put("dns",JSONObject(it.toString()))}
            val carrier=if(standaloneMain==null) {
                require(source.optJSONObject("mcrf_carrier")?.optString("outbound_tag")==tunnelTag){ "БС: подготовка должна относиться к выбранному подключению." }
                resolvedCarrier?:source.getJSONObject("mcrf_carrier")
            }else {
                require(candidate==null&&resolvedCarrier==null&&!source.has("mcrf_carrier")&&!source.has("mcrf_bs")&&
                    mainEgressId==standaloneMain.id&&tunnelTag==ServerImport.tag(standaloneMain)){"NATIVE_ROUTER_STANDALONE_OWNER"}
                replaceStandaloneOutbound(source,tunnelTag)
                null
            }
            scopeGeneratedCarrierDns(source,tunnelTag)
            source.remove("mcrf_carrier");source.remove("mcrf_bs")
            var compiled=ProfileConfig.build(source.toString(),vpn,port)
            val fixtureAuth=ownedFixture?.auth()
            LocalProxyConfig.apply(compiled,fixtureAuth?:LocalProxySettings.value(context))
            var capturedRouting:JSONObject?=null
            var routeServers=emptyList<ServerImport.Server>()
            var mixedPlan:MixedNativeRoutePlan?=null
            if(applyRoutes){
                val catalogue=Connections(context).apply {load()}
                val stored=JSONObject(requireNotNull(routingState){"БС: нужен точный снимок маршрутизации текущего профиля."}.toString())
                val boundState=RoutingConnectionBinding.prepare(context,stored,catalogue.entries(),true,catalogue.probes())
                val state=ServerRoutingMode.engineLocal(boundState,serverRoutingMode)
                routeServers=RouteServerPlan(state,catalogue.entries()).entries
                if(admitMixedRoutes!=null&&routeServers.isNotEmpty()){
                    require(vpn){"NATIVE_ROUTER_MIXED_VPN_REQUIRED"}
                    mixedPlan=MixedNativeRoutePlan.capture(stored,catalogue.entries())
                    require(mixedPlan.entries().map {it.id}==routeServers.map {it.id}){"NATIVE_ROUTER_MIXED_SCOPE_CHANGED"}
                }
                current()
                compiled=RouteRules.apply(compiled,state,vpn,PresetAssets.resolve(context,state),PresetAssets.ipRuleSetTags(context,state),DirectDnsNetwork.ipv4Only(context,physical),catalogue.entries(),nativeBs=true)
                capturedRouting=JSONObject(boundState.toString())
            }else require(egresses.map {it.serverId}==listOfNotNull(mainEgressId))
            val tuning=if(ownedFixture==null)CoreTuningStore(context).load()else CoreTuningSettings()
            val optional=if(ownedFixture==null)OptionalCorePlan(compiled,OptionalCoreStore(context).load(),tuning)else null
            val preparedOptionalSource=compiled.takeIf {mixedPlan!=null}?.let {JSONObject(it.toString())}
            val mixedTags=mixedPlan?.entries()?.mapTo(hashSetOf()) {RouteServerPlan.tag(it.id)}.orEmpty()
            require(optional==null||!optional.requiresWorkers||optional.entries().all {it.tag in mixedTags}){
                "Compiled routes require an unowned optional transport."
            }
            compiled=CoreTuningConfig.apply(compiled,tuning)
            val tun=ClientTunStore(context)
            val configured=ClientTunConfig.apply(compiled,if(ownedFixture==null)tun.load()else ClientTunSettings(mixedPort=port),
                fixtureAuth?:tun.auth(),port)
            ownedFixture?.restrictTun(configured.config,context.packageName)
            val mainKind=carrier?.let(EngineModules::carrierKind)
            compiled=if(routeServers.isEmpty())CarrierAddressFamily.apply(configured.config,mainKind)else {
                val restricted=routeServers.filter {entry->CarrierAddressFamily.needsIpv4(entry.carrier?.let(EngineModules::carrierKind))}
                    .mapTo(linkedSetOf()) {it.id}
                RouteServerAddressFamily.apply(configured.config,tunnelTag,CarrierAddressFamily.needsIpv4(mainKind),restricted)
            }
            current()
            val bound=egresses.map {it.serverId}.toSet()
            val unresolved=routeServers.filter {it.id !in bound}
            val additional=if(unresolved.isEmpty())emptyList()
            else if(mixedPlan!=null)requireNotNull(admitMixedRoutes)(mixedPlan,requireNotNull(preparedOptionalSource),bound)
            else requireNotNull(admitRouteServers) {
                "БС: для дополнительных серверов правил нужны автономные транспорты."
            }(unresolved)
            val allEgresses=egresses+additional
            require(routeServers.map {it.id}.toSet()+listOfNotNull(mainEgressId)==allEgresses.map {it.serverId}.toSet()){
                "БС: состав выходов изменился во время подготовки."
            }
            if(serverRoutingMode!="mcrf"){
                enableServerDomainAttribution(compiled,serverRoutingMode)
                val inbounds=compiled.optJSONArray("inbounds")
                if(inbounds!=null)repeat(inbounds.length()){i->inbounds.getJSONObject(i).takeIf {it.optString("type")=="tun"}?.apply {remove("include_package");remove("exclude_package")}}
            }
            val captured=BsNativeRuleSetFiles.capture(context,compiled,tunnelTag,allEgresses,mainEgressId,cancelled)
            current()
            check(ProfileStore.generation(context)==storageGeneration){"Настройки изменились во время подготовки БС."}
            return BsNativeProfilePreparation(captured,compiled.toString(),configured.localPort?:0,storageGeneration,capturedRouting?.toString())
        }

        internal fun enableServerDomainAttribution(source:JSONObject,mode:String) {
            require(ServerRoutingMode.valid(mode))
            if(mode=="mcrf")return
            // The server policy arrives after capture. Its domain exceptions
            // need the native per-UID, TTL-bound DNS observations even when
            // the local policy contains no domain rules of its own.
            source.optJSONObject("dns")?.put("reverse_mapping",true)
        }

        internal fun replaceStandaloneOutbound(source:JSONObject,tunnelTag:String) {
            require(source.getJSONObject("route").getString("final")==tunnelTag){"NATIVE_ROUTER_STANDALONE_FINAL"}
            val outbounds=source.getJSONArray("outbounds")
            val candidates=(0 until outbounds.length()).filter {outbounds.getJSONObject(it).optString("tag")==tunnelTag}
            val index=candidates.single()
            require(outbounds.getJSONObject(index).getString("type") !in setOf("direct","block","selector","urltest")){
                "NATIVE_ROUTER_STANDALONE_OUTBOUND"
            }
            // Real authenticated ingress is supplied separately by the owned process.
            outbounds.put(index,JSONObject().put("type","socks").put("tag",tunnelTag)
                .put("server","127.0.0.1").put("server_port",1).put("version","5"))
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
