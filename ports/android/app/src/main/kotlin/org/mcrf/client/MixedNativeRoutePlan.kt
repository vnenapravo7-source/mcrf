package org.mcrf.client

import android.content.Context
import android.net.Network
import org.json.JSONObject
import java.util.function.BooleanSupplier

/** Explicit per-rule server bindings admitted by the bundled native router. */
internal class MixedNativeRoutePlan private constructor(
    private val routes:RouteServerPlan,
    private val routingJson:String,
) {
    fun entries():List<ServerImport.Server> = routes.entries

    fun prepare(
        context:Context,
        compiled:JSONObject,
        physical:Network,
        boundIds:Set<String>,
        cancelled:BooleanSupplier,
        strictIngress:Boolean,
    ):Prepared {
        check(!cancelled.asBoolean){"Подготовка серверов правил отменена."}
        val entries=entries().filterNot {it.id in boundIds}
        val carriers=entries.filter {it.carrier!=null}
        carriers.forEach {entry->
            require(BsNativeContract.routerTransport(context,requireNotNull(entry.carrier),true)){
                "Сервер «${entry.name}» не поддерживает автономный выход правила."
            }
        }
        val optional=OptionalCorePlan(compiled,OptionalCoreStore(context).load(),CoreTuningStore(context).load())
            .entries().associateBy {it.tag}
        val routing=JSONObject(routingJson)
        val protocols=entries.filter {it.carrier==null}.associate {entry->
            val request=if(CompactProtocolPolicy.eligible(entry)){
                CompactProtocolSession.standaloneRequest(context,entry,routing)
            }else optional[RouteServerPlan.tag(entry.id)]?.request
            require(request!=null&&request.kind in setOf("xray","tls-core",CompactProtocolPolicy.KIND)){
                "Сервер «${entry.name}» не поддерживает автономный выход правила."
            }
            entry.id to request.copy(physicalNetworkHandle=physical.networkHandle)
        }
        protocols.values.forEach {request->OptionalCoreProcess.check(context,request,cancelled,strictIngress)}
        check(!cancelled.asBoolean&&DirectAccessCheck.physical(context)==physical){
            "Физическая сеть или подключение изменились во время проверки серверов правил."
        }
        return Prepared(carriers,protocols,physical,strictIngress)
    }

    internal class Prepared(
        private val carriers:List<ServerImport.Server>,
        private val protocols:Map<String,OptionalCoreConfig.Request>,
        private val physical:Network,
        private val strictIngress:Boolean,
    ) {
        fun start(context:Context,cancelled:BooleanSupplier):MixedNativeRouteSession {
            val bindings=mutableListOf<BsNativeRoutingSnapshot.Egress>()
            val owners=mutableListOf<AutoCloseable>()
            val protocolsStarted=linkedMapOf<String,OptionalCoreProcess>()
            var carrierSession:RouteServerSession?=null
            try {
                check(!cancelled.asBoolean&&DirectAccessCheck.physical(context)==physical){
                    "Физическая сеть или подключение изменились перед запуском серверов правил."
                }
                if(carriers.isNotEmpty()){
                    val session=RouteServerSession.startForBsRouter(context,carriers,physical,cancelled)
                    owners.add(session)
                    carrierSession=session
                    bindings+=session.bsBindings()
                }
                protocols.forEach { (id,request)->
                    check(!cancelled.asBoolean){"Запуск серверов правил отменён."}
                    val worker=OptionalCoreProcess.start(context,request,cancelled,vpn=true,vpnUidBypassConfigured=false,strictIngress=strictIngress)
                    owners.add(worker)
                    protocolsStarted[id]=worker
                    bindings.add(BsNativeRoutingSnapshot.Egress(id,worker.port,worker.username,worker.password))
                }
                val session=MixedNativeRouteSession(bindings,physical,carrierSession,protocolsStarted,owners)
                check(!cancelled.asBoolean){"Запуск серверов правил отменён."}
                session.requireCurrent(context)
                return session
            }catch(error:Throwable){
                OptionalCoreRetirement.fail(error,OptionalCoreRetirement(owners.asReversed()))
            }
        }
    }

    companion object {
        fun capture(state:JSONObject,catalogue:List<ServerImport.Server>):MixedNativeRoutePlan {
            require(!RoutingConnectionBinding.enabled(state)&&!state.has(RoutingConnectionBinding.RUNTIME)&&
                !state.has(RoutingConnectionBinding.DEFAULT)){
                "Автономные серверы отдельных правил требуют ручных назначений без привязки групп."
            }
            val rules=GeoDataConfig.orderedRules(state)
            repeat(rules.length()){index->
                val rule=rules.getJSONObject(index)
                require(!rule.optBoolean("enabled",true)||!rule.has(RouteServerPlan.POOL)&&!rule.has(RouteServerPlan.AUTO)){
                    "Автономный выход правила поддерживает один явно выбранный сервер."
                }
            }
            return MixedNativeRoutePlan(RouteServerPlan(state,catalogue),state.toString())
        }
    }
}

internal class MixedNativeRouteSession(
    bindings:List<BsNativeRoutingSnapshot.Egress>,
    private val physical:Network,
    private val carriers:RouteServerSession?,
    protocols:Map<String,OptionalCoreProcess>,
    owners:List<AutoCloseable>,
):AutoCloseable {
    private val egresses=bindings.toList()
    private val byId=protocols.toMap()
    private val retirement=OptionalCoreRetirement(owners.asReversed())
    private val failures=RouteWorkerFailures(byId.keys)
    val physicalNetworkHandle:Long get()=physical.networkHandle

    fun bindings():List<BsNativeRoutingSnapshot.Egress> = egresses.toList()
    fun streamProfiles():Map<String,JSONObject> = carriers?.streamProfiles().orEmpty()

    fun requireCurrent(context:Context) {
        check(!retirement.isRequested()&&DirectAccessCheck.physical(context)==physical&&byId.values.all {it.isAlive()}){
            "Сеть или транспорт серверов правил изменились во время запуска."
        }
        carriers?.requireCurrentPhysical(context)
    }

    fun reportFailures(notify:(String)->Unit) {
        if(retirement.isRequested())return
        carriers?.reportFailures {notify("Транспорт сервера правила завершился.")}
        failures.sample(byId.filterValues {it.isAlive()}.keys,retirement.isRequested()).forEach {id->
            notify(byId.getValue(id).diagnostic("транспорт сервера правила")?:"Транспорт сервера правила завершился.")
        }
    }

    override fun close()=retirement.close()
}
