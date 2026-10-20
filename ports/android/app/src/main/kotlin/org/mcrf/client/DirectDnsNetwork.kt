package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet6Address

/** Unknown network state never pretends that IPv6 is unavailable. */
object DirectDnsNetwork {
    fun ipv4Only(context:Context,physical:Network?=null):Boolean = try {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val network=physical?:DirectAccessCheck.physical(context)
        val caps=network?.let(manager::getNetworkCapabilities)
        val properties=network?.let(manager::getLinkProperties)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)==true&&properties!=null&&
            properties.routes.none {it.isDefaultRoute&&it.destination.address is Inet6Address}
    }catch(_:Exception){false}
}
