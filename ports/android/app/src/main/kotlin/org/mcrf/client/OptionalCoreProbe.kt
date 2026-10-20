package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.function.BooleanSupplier

/** Probe-only preparation; caller owns the active-VPN physical-network guard. */
object OptionalCoreProbe {
    class Session internal constructor(val config: JSONObject,val neededKinds: Set<String>,val notes: List<String>,private val native: OptionalCoreSession):AutoCloseable {
        override fun close()=native.close()
        fun isAlive():Boolean=native.isAlive()
        fun isClosed():Boolean=native.isClosed()
    }
    /** Pure module discovery for ProbeCoordinator before its request is queued. */
    fun needed(config: JSONObject,settings: OptionalCoreSettings):Set<String> = OptionalCorePlan(config,settings).entries().map {it.request.kind}.toSet()
    /** No TUN/LAN/custom ingress, control APIs or user's NTP periodic task. */
    fun isolatedConfig(source: JSONObject):JSONObject = JSONObject(source.toString()).apply {
        put("inbounds",JSONArray());remove("experimental");remove("services");remove("ntp")
    }
    fun start(context: Context,config: JSONObject,cancelled: BooleanSupplier):Session = start(context,config,OptionalCoreStore(context).load(),cancelled)
    fun start(context: Context,config: JSONObject,settings: OptionalCoreSettings,cancelled: BooleanSupplier):Session {
        return start(context,config,settings,CoreTuningStore(context).load(),cancelled)
    }
    fun start(context: Context,config: JSONObject,settings: OptionalCoreSettings,core: CoreTuningSettings,cancelled: BooleanSupplier):Session {
        val plan=OptionalCorePlan(isolatedConfig(config),settings,core)
        val native=OptionalCoreSession.start(context,plan,cancelled,false,false)
        return try {Session(native.config,plan.entries().map {it.request.kind}.toSet(),plan.notes,native)} catch(error:Throwable) {OptionalCoreRetirement.fail(error,native)}
    }
}
