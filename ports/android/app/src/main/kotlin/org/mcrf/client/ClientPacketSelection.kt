package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject

/** Shared ordinary-profile admission, performed off UI before storing or opening a VPN. */
object ClientPacketSelection {
    class Prepared(val plan:CompactWireGuardPlan.Plan,val routing:PacketRoutingSnapshot.Result,val module:EngineUpdatePacket.Snapshot,val storageGeneration:String,val includedApps:List<String>) {
        override fun toString()="PacketSelection(redacted)"
    }
    internal class Settings(val routing:JSONObject,val tun:ClientTunSettings,val core:CoreTuningSettings,val generation:String)
    internal fun settings(context:Context,effectiveRouting:(JSONObject)->JSONObject):Settings = synchronized(ProfileStore.storageLock) {
        val store=RoutingStore(context);store.load()
        val routing=JSONObject(effectiveRouting(store.snapshot()).toString())
        Settings(routing,ClientTunStore(context).load(),CoreTuningStore(context).load(),ProfileStore.generation(context))
    }

    private fun allowedApps(context:Context,scope:PacketRoutingSnapshot.AppScope):List<String> {
        val allowed=scope.allowedApps(context.packageName)
        if(allowed.isEmpty())return allowed
        val manager=context.packageManager
        val packages=allowed.toSet()
        allowed.map {name->
            try {manager.getPackageUid(name,0)}
            catch(_:PackageManager.NameNotFoundException) {
                throw IllegalArgumentException("Компактный WG: выбранное приложение не установлено — $name.")
            }
        }.distinct().forEach {uid->
            val aliases=manager.getPackagesForUid(uid)
            require(!aliases.isNullOrEmpty()&&aliases.all(packages::contains)) {
                "Компактный WG: включи все приложения с общим UID или исключи их из выбора."
            }
        }
        return allowed
    }
    private fun routing(plan:CompactWireGuardPlan.Plan,settings:Settings,module:EngineUpdatePacket.Snapshot):PacketRoutingSnapshot.Result {
        val routes=PacketRoutingSnapshot.capture(settings.routing,plan,module.admission,module.managedDns,settings.tun.ipv4Compatibility)
        PacketTunSnapshot.validate(plan,routes,settings.tun,settings.core)
        return routes
    }
    internal fun prepare(context:Context,original:String,dial:String,settings:Settings):Prepared {
        require(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Проверка WG/AWG выполняется в фоне."}
        val plan=CompactWireGuardPlan.prepare(WireGuardProfile.parse(original),dial)
        // APK hashing/native identity IO never holds the UI/storage lock.
        val module=EngineUpdatePacket.capture(context)
        val routes=routing(plan,settings,module)
        return Prepared(plan,routes,module,settings.generation,allowedApps(context,routes.apps))
    }
    /** Choosing Steer may precede downloading its optional WG addon. This is
     * configuration validation only, never a token authorizing native start. */
    fun validateConfiguration(context:Context,original:String,dial:String) {
        require(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Проверка WG/AWG выполняется в фоне."}
        val plan=CompactWireGuardPlan.prepare(WireGuardProfile.parse(original),dial)
        val captured=settings(context){ordinary->ProfilePolicyRuntime.routing(context){ordinary}}
        val routing=PacketRoutingSnapshot.prepare(captured.routing,plan,captured.tun.ipv4Compatibility)
        PacketTunSnapshot.validatePrepared(plan,routing,captured.tun,captured.core)
        allowedApps(context,routing.apps)
    }
    /** A domain is resolved later on the captured physical network, never through a guessed OS resolver. */
    fun validationDial(host:String):String=RouteRules.literal(host)?.let {
        require(it is java.net.Inet4Address) {"Внешний IPv6 сервера компактного WG ещё не прошёл приёмку."}
        requireNotNull(it.hostAddress)
    }?:"192.0.2.1"
}
