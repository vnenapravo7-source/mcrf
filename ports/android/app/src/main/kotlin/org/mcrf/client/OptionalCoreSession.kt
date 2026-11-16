package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.util.function.BooleanSupplier

/** Owned orchestration for a full config. Main retains this alongside its libbox instance. */
class OptionalCoreSession private constructor(val config: JSONObject,private val byTag: Map<String,OptionalCoreProcess>,val physicalNetworkHandle:Long?):AutoCloseable {
    val requiresUidBypass:Boolean get()=physicalNetworkHandle==null
    private val workers=byTag.values.toList()
    private val failures=RouteWorkerFailures(byTag.keys)
    private val retirement=OptionalCoreRetirement(workers.asReversed())
    fun isAlive():Boolean = !retirement.isRequested() && workers.all {it.isAlive()}
    fun isClosed():Boolean=retirement.isComplete()
    fun failureDetail(tag:String):String?=byTag[tag]?.failure()
    fun failureDetails():String=byTag.values.mapNotNull {it.failure()}.distinct().take(3).joinToString(" | ")
    /** Persistent observation, unlike one-shot reportFailures notification delivery. */
    fun deadTags():Set<String> = if(retirement.isRequested())emptySet() else byTag.filterValues {!it.isAlive()}.keys.toSet()
    /** A failed per-rule backend must stay failed, never silently use main. */
    fun reportFailures(notify:(String)->Unit) {
        failures.sample(byTag.filterValues {it.isAlive()}.keys,retirement.isRequested()).forEach(notify)
    }
    override fun close()=retirement.close()
    fun networkChanged()=close()
    companion object {
        fun check(context: Context,plan: OptionalCorePlan,cancelled: BooleanSupplier) = plan.preflight(cancelled) { OptionalCoreProcess.check(context,it.request,cancelled) }
        fun start(context: Context,plan: OptionalCorePlan,cancelled: BooleanSupplier,vpn: Boolean,vpnUidBypassConfigured: Boolean,
                  physicalNetworkHandle:Long?=null):OptionalCoreSession {
            // Check every required module/config before starting even the first listener.
            plan.preflight(cancelled) {
                OptionalCoreProcess.check(context,it.request.copy(physicalNetworkHandle=physicalNetworkHandle),cancelled)
            }
            val workers=mutableListOf<OptionalCoreProcess>();val bridges=linkedMapOf<String,JSONObject>()
            val byTag=linkedMapOf<String,OptionalCoreProcess>()
            try {
                plan.entries().forEach {entry ->
                    require(!cancelled.asBoolean) { "Запуск optional core отменён." }
                    val request=entry.request.copy(physicalNetworkHandle=physicalNetworkHandle)
                    val worker=OptionalCoreProcess.start(context,request,cancelled,vpn,vpnUidBypassConfigured)
                    workers.add(worker);byTag[entry.tag]=worker;bridges[entry.tag]=worker.bridgeOutbound(entry.tag)
                }
                require(!cancelled.asBoolean) { "Запуск optional core отменён." }
                return OptionalCoreSession(plan.replace(bridges),byTag.toMap(),physicalNetworkHandle)
            } catch(error:Throwable) {
                OptionalCoreRetirement.fail(error,OptionalCoreRetirement(workers.asReversed()))
            }
        }
    }
}
