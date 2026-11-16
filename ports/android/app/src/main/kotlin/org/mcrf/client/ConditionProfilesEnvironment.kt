package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/** Fresh physical evidence; starts only the process-lifetime Beta observer, never a VPN. */
internal object ConditionProfilesEnvironment {
    fun read(context:Context,evidence:Map<Network,NetworkCapabilities> = emptyMap()):ConditionEnvironment = runCatching {
        BehaviorScheduleRuntime.start(context)
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val physical=manager.allNetworks.mapNotNull {network->(evidence[network]?:manager.getNetworkCapabilities(network))?.let {network to it}}.filter {(_,caps)->
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val default=DirectAccessCheck.physical(context)
        val chosen=physical.firstOrNull {it.first==default}
        val networks=listOfNotNull(chosen?.second).filter {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val wifi=networks.filter {it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}
        val mobile=networks.filter {it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}
        val allowed=BsWifiMonitor.locationAvailable(context)
        val names=if(allowed)wifi.mapNotNull {caps->runCatching {
            val info=if(Build.VERSION.SDK_INT>=31)caps.transportInfo as? WifiInfo else
                @Suppress("DEPRECATION") (context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo)
            info?.ssid?.let(BsWifiMonitor::ssid)
        }.getOrNull()}.toSet()else emptySet()
        // Permission/location may be revoked during the read. Discard, never cache, names.
        val stillAllowed=allowed&&BsWifiMonitor.locationAvailable(context)
        ConditionEnvironment(wifi.isNotEmpty(),mobile.isNotEmpty(),
            if(mobile.isEmpty())null else mobile.any {!it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)},
            if(stillAllowed)names else emptySet(),stillAllowed)
    }.getOrDefault(ConditionEnvironment())
}
