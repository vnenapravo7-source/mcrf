package org.mcrf.client

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object LanSharingAddresses {
    fun allowed(name:String,up:Boolean,loopback:Boolean,address:InetAddress):Boolean =
        up&&!loopback&&listOf("tun","wg","rmnet","ccmni","pdp").none {name.lowercase(Locale.ROOT).startsWith(it)}&&
            address is Inet4Address&&address.isSiteLocalAddress&&!address.isLoopbackAddress
    fun discover():List<String> = Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap {network->
        Collections.list(network.inetAddresses).filter {allowed(network.name,network.isUp,network.isLoopback,it)}.mapNotNull {it.hostAddress}
    }.distinct().sorted()
}
