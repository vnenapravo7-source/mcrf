// SPDX-License-Identifier: GPL-3.0-only
// Android route-family/exclude semantics adapted from qThroneMobile VpnService.kt,
// v1.4.2-beta.2, c0edb581a45b53c1064b927c25df80b2fd8a55dd, GPL-3.0.
// Upstream: https://github.com/ttrroyy/qThroneMobile (SagerNet/nekohasekai lineage).
package org.mcrf.client

import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.TunOptions
import java.net.InetAddress

/** Coordinator calls after addAddress; replaces unconditional IPv4/IPv6 default routes + DNS. */
object ClientTunPlatform {
    fun applyRoutesAndProxy(builder: VpnService.Builder, options: TunOptions) {
        fun prefixes(iterator: RoutePrefixIterator, action: (String,Int) -> Unit) { while(iterator.hasNext()) { val prefix = iterator.next(); action(prefix.address(),prefix.prefix()) } }
        if (options.getAutoRoute()) {
            if (options.getDNSMode().getValue() != "disabled") { val dns = options.getDNSServerAddress(); while(dns.hasNext()) builder.addDnsServer(dns.next()) }
            if (Build.VERSION.SDK_INT >= 33) {
                val v4 = options.getInet4RouteAddress()
                if(v4.hasNext()) prefixes(v4) { host,prefix -> builder.addRoute(host,prefix) }
                else if(options.getInet4Address().hasNext()) builder.addRoute("0.0.0.0",0)
                val v6 = options.getInet6RouteAddress()
                if(v6.hasNext()) prefixes(v6) { host,prefix -> builder.addRoute(host,prefix) }
                else if(options.getInet6Address().hasNext()) builder.addRoute("::",0)
                fun exclude(iterator: RoutePrefixIterator) = prefixes(iterator) { host,prefix -> val address = InetAddress.getByName(host); if(!address.isLoopbackAddress) builder.excludeRoute(IpPrefix(address,prefix)) }
                exclude(options.getInet4RouteExcludeAddress()); exclude(options.getInet6RouteExcludeAddress())
            } else {
                prefixes(options.getInet4RouteRange()) { host,prefix -> if(!InetAddress.getByName(host).isLoopbackAddress) builder.addRoute(host,prefix) }
                prefixes(options.getInet6RouteRange()) { host,prefix -> if(!InetAddress.getByName(host).isLoopbackAddress) builder.addRoute(host,prefix) }
            }
        }
        if (options.isHTTPProxyEnabled()) {
            val domains = mutableListOf<String>(); val iterator = options.getHTTPProxyBypassDomain(); while(iterator.hasNext()) iterator.next().takeIf { it.isNotBlank() }?.let(domains::add)
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(options.getHTTPProxyServer(),options.getHTTPProxyServerPort(),domains))
        }
        // Do not call allowFamily(AF_INET6): absent addresses/routes must block IPv6, not leak it.
    }
}
