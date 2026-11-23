package org.mcrf.client

import android.content.Context
import android.net.Network
import org.json.JSONObject
import java.util.function.BooleanSupplier

/** Additional protocol transports only: authenticated loopback SOCKS, NEVER
 * another Android VPN/TUN. Primary engine retains routing/DNS. Partial starts
 * retain all exact owners until real retirement. No catalogue/preferences write. */
internal class RouteServerSession private constructor(val config:JSONObject,private val byId:Map<String,NativeCarrier>,
    private val startedProfiles:Map<String,JSONObject>,private val underlayOwnedIds:Set<String>,
    val boundPhysical:Network?):AutoCloseable {
    private val workers=byId.values.toList()
    fun actualStreams(id:String):Int=byId[id]?.actualStreams()?:-1
    fun streamProfiles():Map<String,JSONObject> = startedProfiles.mapValues {JSONObject(it.value.toString())}
    private val retirement=OptionalCoreRetirement(workers.asReversed())
    private val reported=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NativeCarrier,Boolean>())
    val requiresUidBypass:Boolean get()=byId.keys.any {it !in underlayOwnedIds}
    fun requireCurrentPhysical(context:Context) {
        check(boundPhysical==null||DirectAccessCheck.physical(context)==boundPhysical){
            "Физическая сеть изменилась во время запуска серверов правил."
        }
    }
    fun bsBindings():List<BsNativeRoutingSnapshot.Egress> {
        check(byId.keys==underlayOwnedIds)
        return byId.map { (id,worker)->BsNativeRoutingSnapshot.Egress(id,worker.port,worker.username,worker.password) }
    }
    fun attachOriginalPackets(worker:ClientPrimaryWorker,settings:String,cancelled:BooleanSupplier) {
        byId.forEach {(id,owner)->if(id !in underlayOwnedIds)owner.attachOriginalPackets(worker,settings,RouteServerPlan.tag(id),cancelled)}
    }
    fun reportFailures(notify:()->Unit) {
        workers.forEach {owner->if(owner.runtimeFailure()!=null&&reported.add(owner))notify()}
    }
    override fun close()=retirement.close()
    companion object {
        /** Runtime-only snapshot; never rewrite the saved server or its override. */
        internal fun streamProfile(context:Context,profile:JSONObject):JSONObject = try {
            val concrete=if(CsqttAutoDetection.negotiates(profile)) {
                val target=CsqttAutoRuntime.cached(context,profile)
                if(target==null)JSONObject(profile.toString())else CsqttAutoDetection.profile(profile,target)
            }else JSONObject(profile.toString())
            if(concrete.optString("type") in SwgProfile.TYPES)
                VkRelayRuntime.withManualSupplements(concrete,VkRelayAccount(AndroidDeployStore(context)))else concrete
        }catch(_:Exception){
            JSONObject(profile.toString()).put(CarrierStreamRestartPolicy.UNVERIFIED,true)
        }
        fun check(context:Context,plan:RouteServerPlan,cancelled:BooleanSupplier) {
            plan.entries.forEach {entry->
                check(!cancelled.asBoolean) {"Запуск серверов правил отменён."}
                entry.carrier?.let {
                    val kind=EngineModules.carrierKind(it)
                    EnginePackages.missingForProfile(context,it).firstOrNull()?.let {missing->throw ClientOptionalCoreRequired(missing)}
                    NativeCarrier.executable(context,kind)
                    NativeCarrier.requireOptions(context,it)
                }
            }
            check(!cancelled.asBoolean) {"Запуск серверов правил отменён."}
        }
        fun start(context:Context,source:JSONObject,plan:RouteServerPlan,cancelled:BooleanSupplier,originalGeneration:Long=0,
                  allowAutonomousUnderlay:Boolean=false,prepareLegacyUnderlay:()->Unit={}):RouteServerSession {
            check(context,plan,cancelled)
            val workers=mutableListOf<NativeCarrier>();val config=JSONObject(source.toString())
            try {
                val carriers=plan.entries.filter {it.carrier!=null}.associateBy {it.id}
                val autonomous=if(allowAutonomousUnderlay)carriers.filterValues {entry->
                    BsNativeContract.routerTransport(context,requireNotNull(entry.carrier),true)
                }.keys.toSet()else emptySet()
                val physical=if(autonomous.isEmpty())null else requireNotNull(DirectAccessCheck.physical(context)) {
                    "Для автономных серверов правил нужна физическая сеть."
                }
                if(carriers.keys.any {it !in autonomous})prepareLegacyUnderlay()
                val ready=ConnectTiming.measure(context,"route_carriers") {
                    RouteCarrierBatch.start(carriers.keys.toList(),cancelled){id,stop->
                        val profile=JSONObject(requireNotNull(carriers.getValue(id).carrier).toString())
                        val boundNetwork=physical.takeIf {id in autonomous}
                        if(plan.poolOnly(id))NativeCarrier.startPoolMember(context,profile,stop,boundNetwork)
                        else if(boundNetwork!=null)NativeCarrier.startForBsRouter(context,profile,boundNetwork,stop)
                        else NativeCarrier.startRouted(context,profile,stop,originalGeneration)
                    }
                }
                workers.addAll(ready.values)
                carriers.values.forEach {entry->
                    check(!cancelled.asBoolean) {"Запуск серверов правил отменён."}
                    val owner=ready[entry.id]
                    if(owner==null){
                        // Leave only an unreachable authenticated dummy bridge;
                        // URL-test cannot route this member through global/Direct.
                        ClientEvents.record(context,"Маршруты","Один из серверов группы недоступен; проверяем остальные выбранные серверы.")
                        return@forEach
                    }
                    val nodes=config.getJSONArray("outbounds")
                    val node=(0 until nodes.length()).map {nodes.getJSONObject(it)}.single {it.getString("tag")==RouteServerPlan.tag(entry.id)}
                    check(node.optString("type")=="socks"&&node.optString("server")=="127.0.0.1") {"Сервер правила не имеет локального моста."}
                    node.put("server_port",owner.port).put("username",owner.username).put("password",owner.password)
                }
                check(!cancelled.asBoolean) {"Запуск серверов правил отменён."}
                val underlayOwnedIds=autonomous.intersect(ready.keys)
                return RouteServerSession(config,ready.toMap(),carriers.filterKeys {it in ready}.mapValues {
                    ready.getValue(it.key).streamProfile()
                        ?:streamProfile(context,requireNotNull(it.value.carrier)).put(CarrierStreamRestartPolicy.UNVERIFIED,true)
                },underlayOwnedIds,physical.takeIf {underlayOwnedIds.isNotEmpty()}).also {it.requireCurrentPhysical(context)}
            }catch(error:Throwable){OptionalCoreRetirement.fail(error,OptionalCoreRetirement(workers.asReversed()))}
        }

        fun startForBsRouter(context:Context,entries:List<ServerImport.Server>,physical:Network,cancelled:BooleanSupplier):RouteServerSession {
            val profiles=entries.associate {entry->
                val profile=entry.carrier?.let {JSONObject(it.toString())}
                require(profile!=null&&BsNativeContract.routerTransport(context,profile,true)){
                    "БС: для сервера «${entry.name}» нет транспорта с автономной маршрутизацией."
                }
                entry.id to profile
            }
            val workers=RouteCarrierBatch.start(profiles.keys.toList(),cancelled){id,stop->
                NativeCarrier.startForBsRouter(context,profiles.getValue(id),physical,stop)
            }
            try {
                check(!cancelled.asBoolean&&DirectAccessCheck.physical(context)==physical&&workers.keys==profiles.keys){
                    "БС: набор серверов или физическая сеть изменились во время запуска."
                }
                val streamProfiles=workers.mapNotNull { (id,worker)->worker.streamProfile()?.let {id to it} }.toMap()
                return RouteServerSession(JSONObject(),workers,streamProfiles,workers.keys.toSet(),physical)
            }catch(error:Throwable){OptionalCoreRetirement.fail(error,OptionalCoreRetirement(workers.values.toList().asReversed()))}
        }
    }
}
