package org.mcrf.client

import android.content.Context
import org.json.JSONObject
import java.util.function.BooleanSupplier

/** Owned orchestration for a full config. Main retains this alongside its libbox instance. */
class OptionalCoreSession private constructor(val config: JSONObject,private val workers: List<OptionalCoreProcess>):AutoCloseable {
    private val retirement=OptionalCoreRetirement(workers.asReversed())
    fun isAlive():Boolean = !retirement.isRequested() && workers.all {it.isAlive()}
    fun isClosed():Boolean=retirement.isComplete()
    override fun close()=retirement.close()
    fun networkChanged()=close()
    companion object {
        fun check(context: Context,plan: OptionalCorePlan,cancelled: BooleanSupplier) = plan.preflight(cancelled) { OptionalCoreProcess.check(context,it.request,cancelled) }
        fun start(context: Context,plan: OptionalCorePlan,cancelled: BooleanSupplier,vpn: Boolean,vpnUidBypassConfigured: Boolean):OptionalCoreSession {
            // Check every required module/config before starting even the first listener.
            check(context,plan,cancelled)
            val workers=mutableListOf<OptionalCoreProcess>();val bridges=linkedMapOf<String,JSONObject>()
            try {
                plan.entries().forEach {entry ->
                    require(!cancelled.asBoolean) { "Запуск optional core отменён." }
                    val worker=OptionalCoreProcess.start(context,entry.request,cancelled,vpn,vpnUidBypassConfigured)
                    workers.add(worker);bridges[entry.tag]=worker.bridgeOutbound(entry.tag)
                }
                require(!cancelled.asBoolean) { "Запуск optional core отменён." }
                return OptionalCoreSession(plan.replace(bridges),workers.toList())
            } catch(error:Throwable) {
                OptionalCoreRetirement.fail(error,OptionalCoreRetirement(workers.asReversed()))
            }
        }
    }
}
