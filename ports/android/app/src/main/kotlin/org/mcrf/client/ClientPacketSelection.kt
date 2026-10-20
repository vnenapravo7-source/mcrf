package org.mcrf.client

import android.content.Context

/** Shared ordinary-profile admission, performed off UI before storing or opening a VPN. */
object ClientPacketSelection {
    class Prepared(val plan:CompactWireGuardPlan.Plan,val routing:PacketRoutingSnapshot.Result,val module:EngineUpdatePacket.Snapshot,val storageGeneration:String) {
        override fun toString()="PacketSelection(redacted)"
    }
    private class Settings(val routing:org.json.JSONObject,val tun:ClientTunSettings,val core:CoreTuningSettings,val generation:String)
    private fun settings(context:Context):Settings = synchronized(ProfileStore.storageLock) {
        val store=RoutingStore(context);store.load()
        Settings(store.snapshot(),ClientTunStore(context).load(),CoreTuningStore(context).load(),ProfileStore.generation(context))
    }
    private fun routing(context:Context,plan:CompactWireGuardPlan.Plan,settings:Settings,module:EngineUpdatePacket.Snapshot):PacketRoutingSnapshot.Result {
        val routes=PacketRoutingSnapshot.capture(settings.routing,plan,module.admission,module.managedDns,settings.tun.ipv4Compatibility)
        PacketTunSnapshot.validate(plan,routes,settings.tun,settings.core)
        require(context.packageName !in routes.directApps) {"Компактному WG нужен доступ MCRF к собственной сети VPN."}
        return routes
    }
    fun prepare(context:Context,original:String,dial:String):Prepared {
        require(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Проверка WG/AWG выполняется в фоне."}
        val plan=CompactWireGuardPlan.prepare(WireGuardProfile.parse(original),dial)
        // APK hashing/native identity IO never holds the UI/storage lock.
        val module=EngineUpdatePacket.capture(context)
        val settings=settings(context)
        val routes=routing(context,plan,settings,module)
        return Prepared(plan,routes,module,settings.generation)
    }
    /** Choosing Steer may precede downloading its optional WG addon. This is
     * configuration validation only, never a token authorizing native start. */
    fun validateConfiguration(context:Context,original:String,dial:String) {
        require(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Проверка WG/AWG выполняется в фоне."}
        val plan=CompactWireGuardPlan.prepare(WireGuardProfile.parse(original),dial)
        val captured=settings(context)
        val routing=PacketRoutingSnapshot.prepare(captured.routing,plan,captured.tun.ipv4Compatibility)
        PacketTunSnapshot.validatePrepared(plan,routing,captured.tun,captured.core)
        require(context.packageName !in routing.directApps){"Компактному WG нужен доступ MCRF к собственной сети VPN."}
    }
    /** A domain is resolved later on the captured physical network, never through a guessed OS resolver. */
    fun validationDial(host:String):String=RouteRules.literal(host)?.let {
        require(it is java.net.Inet4Address) {"Внешний IPv6 сервера компактного WG ещё не прошёл приёмку."}
        requireNotNull(it.hostAddress)
    }?:"192.0.2.1"
}
