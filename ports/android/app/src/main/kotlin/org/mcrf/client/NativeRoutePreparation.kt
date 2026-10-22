package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Engine
import java.util.function.BooleanSupplier

/** Shared, side-effect-free catalogue/routing capture for the Steer protocol
 * workers + external dispatcher path. Does NOT edit the user's primary choice,
 * route servers, marks, selected server, TUN policy or source profiles. */
internal class NativeRoutePreparation private constructor(
    val plan:NativeRouteDispatchPlan,val steerSha:String,private val routing:JSONObject,
    private val servers:List<ServerImport.Server>,private val template:JSONObject
) {
    fun source():JSONObject=JSONObject(template.toString())
    fun state():JSONObject=JSONObject(routing.toString())
    fun entries():List<ServerImport.Server> = RouteServerPlan(routing,servers).entries
    /** Only native schema validation. These private dummy endpoints MUST NEVER
     * start: runtime replaces them with exact worker listener/credential ACKs. */
    fun validate(context:Context,vpn:Boolean,port:Int,cancelled:BooleanSupplier) {
        check(!cancelled.asBoolean){"Подготовка подключения отменена."}
        var config=ProfileConfig.build(template.toString(),vpn,port)
        config=RouteRules.apply(config,routing,vpn,PresetAssets.resolve(context,routing),PresetAssets.ipRuleSetTags(context,routing),DirectDnsNetwork.ipv4Only(context),servers)
        RouteServerSession.check(context,RouteServerPlan(routing,servers),cancelled)
        val bootstrap=ServerBootstrapPolicy.capture(routing)
        val tuning=CoreTuningStore(context).load();SteerRouteTuning.requireSupported(tuning)
        val optional=OptionalCorePlan(config,OptionalCoreStore(context).load(),tuning)
        val optionalBridges=linkedMapOf<String,JSONObject>()
        optional.preflight(cancelled){entry->
            OptionalCoreProcess.check(context,entry.request,cancelled)
            optionalBridges[entry.tag]=OptionalCoreConfig.bridgeOutbound(entry.tag,10900+optionalBridges.size,
                java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),entry.request.kind)
        }
        config=CoreTuningConfig.apply(optional.replace(optionalBridges),tuning)
        val bridges=plan.bindings.mapIndexed {index,binding->
            val credentials=SteerRouteBackendSpec.create()
            binding.id to JSONObject().put("type","socks").put("server","127.0.0.1").put("version","5")
                .put("server_port",12000+index).put("username",credentials.username).put("password",credentials.password)
        }.toMap()
        config=plan.replace(config,bridges)
        val tun=ClientTunStore(context)
        config=ClientTunConfig.apply(config,tun.load(),tun.auth(),port).config
        check(!cancelled.asBoolean){"Подготовка подключения отменена."}
        val worker=ClientPrimaryWorker.connect(context,"singbox",System.nanoTime().coerceAtLeast(1),null,
            ClientPrimaryValidationWorkerService::class.java)
        try{
            if(bootstrap.requiresCore){
                val dns=worker.validate("",ClientPrimarySelection.envelope(bootstrap.coreConfig(11999,"validation","validation"),false,false),Engine.Mode.PROXY)
                require(dns.supported){"Выбранный DNS для подключения к серверам не может работать до запуска серверов. Проверьте ссылки DNS-JSON на прокси/другие DNS; системная подмена не выполняется."}
            }
            val validation=worker.validate("",ClientPrimarySelection.envelope(config,vpn,ClientBehaviorStore.load(context).metered),if(vpn)Engine.Mode.VPN else Engine.Mode.PROXY)
            require(validation.supported){"Диспетчер не поддерживает подготовленные правила (${validation.code}). Ничего не изменено."}
            check(!cancelled.asBoolean){"Подготовка подключения отменена."}
        }finally{worker.close()}
    }
    override fun toString()="NativeRoutePreparation(redacted)"
    companion object {
        fun capture(context:Context,main:ServerImport.Server,routing:JSONObject,servers:List<ServerImport.Server>):NativeRoutePreparation {
            val state=JSONObject(routing.toString())
            require(RouteServerPlan.activeIds(state).isNotEmpty()){"NATIVE_ROUTE_EXPLICIT_SERVERS_REQUIRED"}
            val core=CoreTuningStore(context).load();SteerRouteTuning.requireSupported(core)
            if(EnginePackages.missing(context,setOf("sing-box")).isNotEmpty())throw ClientOptionalCoreRequired("sing-box")
            val pin=NativeRouteWorkers.modulePin(context)
            val source=ServerImport.build(listOf(main),main.id,false,60,50,"Cloudflare")
            DnsConfig.applyGenerated(source,state.getJSONObject("globalDns"))
            val additional=RouteServerPlan(state,servers)
            val plan=NativeRouteDispatchPlan.forSteer(main,additional,OptionalCoreStore(context).load(),core)
            // RouteServerPlan already owns defensive per-rule copies. Rebuild
            // the minimal independent catalogue so a later UI edit cannot alter
            // this captured preparation through a mutable JSONObject reference.
            return NativeRoutePreparation(plan,pin,state,additional.entries,source)
        }
    }
}
