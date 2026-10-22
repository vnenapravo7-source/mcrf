package org.mcrf.client

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.*
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Service-owned physical monitor survives retirement of the paused tunnel's platform. */
internal class BsWifiMonitor(private val context:Context,private val settings:BsWifiSettings,private val changed:()->Unit):AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(ConnectivityManager::class.java)
    private val appOps=context.getSystemService(AppOpsManager::class.java)
    private val networks=linkedMapOf<Network,NetworkCapabilities>()
    private var closed=false
    private var registered=false
    private var receiverRegistered=false
    private var permissionsRegistered=false
    @Volatile var snapshot=Snapshot(false,emptySet());private set
    data class Snapshot(val wifi:Boolean,val ssids:Set<String>)
    private fun update(network:Network,caps:NetworkCapabilities?) {
        if(closed)return
        if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            networks[network]=caps
        }else networks.remove(network)
        publish()
    }
    private fun publish() {
        val names=if(settings.trustedEnabled&&locationAvailable(context))networks.values.mapNotNull {caps->runCatching {
            val info=if(Build.VERSION.SDK_INT>=31)caps.transportInfo as? WifiInfo
                else @Suppress("DEPRECATION") (context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo)
            info?.ssid?.let(::ssid)
        }.getOrNull()}.toSet()else emptySet()
        val next=Snapshot(networks.isNotEmpty(),names)
        if(next!=snapshot){snapshot=next;changed()}
    }
    private fun lost(network:Network){if(!closed){networks.remove(network);publish()}}
    private val environmentRefresh=Runnable {
        if(!closed)runCatching {
            // Recreate this owned monitor: a new callback generation obtains
            // fresh Android-redacted transport info. Never reuse a cached SSID
            // across a permission/location/foreground-mode transition.
            McrfVpnService.refreshBsEnvironment(context)
        }
    }
    private fun invalidateLocation(){
        if(closed)return
        if(snapshot.ssids.isNotEmpty()){snapshot=Snapshot(snapshot.wifi,emptySet());changed()}
        handler.removeCallbacks(environmentRefresh);handler.post(environmentRefresh)
    }
    private val locationReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){invalidateLocation()}}
    // Public own-UID API; PackageManager's permission-change listener is not in
    // the normal Android SDK. No WATCH_APPOPS privilege or location polling.
    // https://developer.android.com/reference/android/app/AppOpsManager#startWatchingMode(java.lang.String,java.lang.String,int,android.app.AppOpsManager.OnOpChangedListener)
    private val permissions=AppOpsManager.OnOpChangedListener {_,packageName->
        if(packageName==null||packageName==context.packageName)handler.post {invalidateLocation()}
    }
    private val callback=if(Build.VERSION.SDK_INT>=31)object:ConnectivityManager.NetworkCallback(
        // Android still redacts unless permission AND location are enabled.
        // https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback#FLAG_INCLUDE_LOCATION_INFO
        if(settings.trustedEnabled)ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO else 0) {
        override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities)=update(network,caps)
        override fun onLost(network:Network)=lost(network)
    }else object:ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities)=update(network,caps)
        override fun onLost(network:Network)=lost(network)
    }
    fun start() {
        check(!closed&&!registered)
        manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback,handler)
        registered=true
        if(settings.trustedEnabled) {
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(locationReceiver,IntentFilter(LocationManager.MODE_CHANGED_ACTION),Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(locationReceiver,IntentFilter(LocationManager.MODE_CHANGED_ACTION))
            receiverRegistered=true
            permissionsRegistered=true
            for(op in listOf(AppOpsManager.OPSTR_FINE_LOCATION,AppOpsManager.OPSTR_COARSE_LOCATION)){
                appOps.startWatchingMode(op,context.packageName,AppOpsManager.WATCH_FOREGROUND_CHANGES,permissions)
            }
        }
        manager.allNetworks.forEach {network->update(network,manager.getNetworkCapabilities(network))}
    }
    override fun close() {
        closed=true
        handler.removeCallbacks(environmentRefresh)
        if(registered){runCatching {manager.unregisterNetworkCallback(callback)};registered=false}
        if(receiverRegistered){runCatching {context.unregisterReceiver(locationReceiver)};receiverRegistered=false}
        if(permissionsRegistered){runCatching {appOps.stopWatchingMode(permissions)};permissionsRegistered=false}
        networks.clear();snapshot=Snapshot(false,emptySet())
    }
    companion object {
        fun locationAvailable(context:Context)=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED&&
            context.getSystemService(LocationManager::class.java).isLocationEnabled
        internal fun ssid(raw:String):String? {
            val value=if(raw.startsWith('"')&&raw.endsWith('"')&&raw.length>=2)raw.substring(1,raw.length-1)else raw
            return value.takeIf {it.isNotBlank()&&it!="<unknown ssid>"&&it.toByteArray(Charsets.UTF_8).size<=32&&it.none(Char::isISOControl)}
        }
    }
}
