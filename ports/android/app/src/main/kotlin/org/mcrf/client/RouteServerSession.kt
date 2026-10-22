package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.util.function.BooleanSupplier

/** Additional protocol transports only: authenticated loopback SOCKS, NEVER
 * another Android VPN/TUN. Primary engine retains routing/DNS. Partial starts
 * retain all exact owners until real retirement. No catalogue/preferences write. */
internal class RouteServerSession private constructor(val config:JSONObject,private val workers:List<NativeCarrier>):AutoCloseable {
    private val retirement=OptionalCoreRetirement(workers.asReversed())
    private val reported=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NativeCarrier,Boolean>())
    val requiresUidBypass:Boolean get()=workers.isNotEmpty()
    fun reportFailures(notify:()->Unit) {
        workers.forEach {owner->if(owner.runtimeFailure()!=null&&reported.add(owner))notify()}
    }
    override fun close()=retirement.close()
    companion object {
        fun check(context:Context,plan:RouteServerPlan,cancelled:BooleanSupplier) {
            plan.entries.forEach {entry->
                check(!cancelled.asBoolean) {"Запуск серверов правил отменён."}
                entry.carrier?.let {
                    val kind=EngineModules.carrierKind(it)
                    if(EnginePackages.missing(context,setOf(kind)).isNotEmpty())throw ClientOptionalCoreRequired(kind)
                    NativeCarrier.executable(context,kind)
                    NativeCarrier.requireOptions(context,it)
                }
            }
            check(!cancelled.asBoolean) {"Запуск серверов правил отменён."}
        }
        fun start(context:Context,source:JSONObject,plan:RouteServerPlan,cancelled:BooleanSupplier):RouteServerSession {
            check(context,plan,cancelled)
            val workers=mutableListOf<NativeCarrier>();val config=JSONObject(source.toString())
            try {
                val carriers=plan.entries.filter {it.carrier!=null}.associateBy {it.id}
                val ready=ConnectTiming.measure(context,"route_carriers") {
                    RouteCarrierBatch.start(carriers.keys.toList(),cancelled){id,stop->
                        val profile=JSONObject(requireNotNull(carriers.getValue(id).carrier).toString())
                        if(plan.poolOnly(id))NativeCarrier.startPoolMember(context,profile,stop)
                        else NativeCarrier.start(context,profile,stop)
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
                return RouteServerSession(config,workers.toList())
            }catch(error:Throwable){OptionalCoreRetirement.fail(error,OptionalCoreRetirement(workers.asReversed()))}
        }
    }
}
