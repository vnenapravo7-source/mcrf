package org.mcrf.engines.singbox

import io.nekohasekai.libbox.*
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.engine.spi.v1.Platform
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/** All gomobile objects stay in this module. Main owns physical networking and TUN. */
class SingBoxPlatformAdapter(private val generation: Long,private val host: Engine.Host,
                             private val platform: Platform,private val config: SingBoxRuntimeConfig,
                             private val localStopping: ()->Boolean = {false}) {
    private val dnsId=AtomicLong()
    @Volatile private var monitor: InterfaceUpdateListener?=null
    private val guard=SingBoxCallbackGuard(generation,{localStopping() || host.stopping(generation)},{platform.protectAndBind(generation,it)})
    private fun current() = guard.current()
    private fun strings(values: List<String>)=object:StringIterator {
        private var index=0
        override fun hasNext()=index<values.size
        override fun next()=values[index++]
        override fun len()=values.size
    }
    private fun list(values: StringIterator): List<String> = buildList {while(values.hasNext())add(values.next())}
    private fun prefixes(values: RoutePrefixIterator): List<String> = buildList {while(values.hasNext()) {
        val value=values.next();add("${value.address()}/${value.prefix()}")
    } }
    fun tunPlan(options: TunOptions): Platform.TunPlan {
        val autoRoute=options.getAutoRoute();val dnsEnabled=options.getDNSMode().getValue()!="disabled"
        // This native getter can throw; unused DNS addresses must not be resolved.
        val dnsServers=if(autoRoute && dnsEnabled)list(options.getDNSServerAddress()) else emptyList()
        val proxyEnabled=options.isHTTPProxyEnabled()
        val httpProxy=SingBoxTunProxy.read(proxyEnabled,{options.getHTTPProxyServer()},
            {options.getHTTPProxyServerPort()},{list(options.getHTTPProxyBypassDomain())})
        return Platform.TunPlan(options.getMTU(),autoRoute,dnsEnabled,config.metered,
        Platform.Routes(prefixes(options.getInet4Address()),prefixes(options.getInet6Address()),prefixes(options.getInet4RouteAddress()),prefixes(options.getInet6RouteAddress()),
            prefixes(options.getInet4RouteExcludeAddress()),prefixes(options.getInet6RouteExcludeAddress()),prefixes(options.getInet4RouteRange()),prefixes(options.getInet6RouteRange())),
        dnsServers,list(options.getIncludePackage()),list(options.getExcludePackage()),
        httpProxy)
    }
    private fun interfaces(): NetworkInterfaceIterator {
        val devices=platform.interfaces(generation).map { value ->
            io.nekohasekai.libbox.NetworkInterface().apply {
                setName(value.name);setIndex(value.index);setMTU(value.mtu);setFlags(value.flags)
                setAddresses(strings(value.addresses));setDNSServer(strings(value.dnsServers));setMetered(value.metered)
                setType(when(value.type) {"wifi"->Libbox.InterfaceTypeWIFI;"cellular"->Libbox.InterfaceTypeCellular;"other"->Libbox.InterfaceTypeOther;else->throw IOException("INTERFACE_TYPE")})
            }
        }
        return object:NetworkInterfaceIterator {private var index=0;override fun hasNext()=index<devices.size;override fun next()=devices[index++]}
    }
    private fun dns()=object:LocalDNSTransport {
        override fun raw()=true
        override fun lookup(context: ExchangeContext,network: String,domain: String): Unit=throw IOException("RAW_DNS_ONLY")
        override fun exchange(context: ExchangeContext,packet: ByteArray) {
            val id=dnsId.incrementAndGet();val completion=SingBoxDnsCompletion()
            context.onCancel { completion.finish { try {platform.cancelDns(generation,id)} finally {context.errnoCode(android.system.OsConstants.ECANCELED)} } }
            // Registration can synchronously report an already-cancelled native context.
            if(!completion.active)return
            try {
                current();if(!completion.active)return
                val reply=platform.exchangeDns(generation,Platform.DnsQuery(id,packet,8000));current()
                completion.finish {
                    if(reply.errno!=0)context.errnoCode(reply.errno) else context.rawSuccess(reply.packet())
                }
            } catch(_:Exception) {completion.finish {context.errnoCode(android.system.OsConstants.EIO)}}
        }
    }
    val native: PlatformInterface=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(PlatformInterface::class.java)) { proxy,method,args ->
        when(method.name) {
            "toString"->"SingBoxPlatformAdapter"
            "hashCode"->System.identityHashCode(proxy)
            "equals"->proxy===args?.get(0)
            "openTun"->{
                val borrowed=guard.tun(config.mode) {platform.openTun(generation,tunPlan(args!![0] as TunOptions)).also {require(it>=0) {"HOST_TUN_FD"}}}
                // Exact pinned libbox service.go duplicates this borrowed fd before tun.New.
                // Keep the IPC-owned worker PFD alive until cleanup; another detached duplicate
                // here would leak because libbox does not close the callback's borrowed fd.
                borrowed
            }
            "usePlatformAutoDetectInterfaceControl"->true
            "autoDetectInterfaceControl"->{guard.protect(args!![0] as Int);null}
            "getInterfaces"->{current();interfaces()}
            "findConnectionOwner"->{current();val a=args!!;val value=platform.connectionOwner(generation,Platform.ConnectionQuery(a[0] as Int,a[1] as String,a[2] as Int,a[3] as String,a[4] as Int))
                ConnectionOwner().apply {setUserId(value.uid);setAndroidPackageNames(strings(value.packageNames))}}
            "startDefaultInterfaceMonitor"->{current();val listener=args!![0] as InterfaceUpdateListener;monitor=listener
                platform.startDefaultMonitor(generation) { eventGeneration,value -> guard.event(eventGeneration,monitor===listener) {listener.updateDefaultInterface(value.name,value.index,value.metered,value.expensive)} };null}
            "closeDefaultInterfaceMonitor"->{close();null}
            "localDNSTransport"->dns()
            "useProcFS"->platform.features(generation).procFs
            "underNetworkExtension"->platform.features(generation).networkExtension
            "includeAllNetworks"->platform.features(generation).includeAllNetworks
            "usePlatformShell","usePlatformBridge"->false // no shell/bridge implementation in this adapter
            "readWIFIState"->platform.wifiState(generation).let {Libbox.newWIFIState(it.ssid,it.bssid)}
            "tailscaleHostname"->platform.hostname(generation)
            "clearDNSCache"->{platform.clearDnsCache(generation);null}
            "sendNotification"->{val notice=args!![0] as io.nekohasekai.libbox.Notification
                require(platform.features(generation).notifications==Platform.Availability.EMPTY_COMPATIBILITY || (notice.getSubtitle().isEmpty() && notice.getOpenURL().isEmpty())) {"NOTIFICATION_EXTRA_FIELDS_UNSUPPORTED"}
                platform.notification(generation,Platform.Notice(notice.getIdentifier(),notice.getTypeID(),notice.getTitle(),notice.getBody()));null}
            "cancelNotification"->{platform.cancelNotification(generation,args!![0] as String,args[1] as Int);null}
            "startNeighborMonitor"->{require(platform.features(generation).neighbors==Platform.Availability.EMPTY_COMPATIBILITY) {"NEIGHBOR_MONITOR_UNSUPPORTED"};null}
            "closeNeighborMonitor"->{platform.closeNeighborMonitor(generation);null}
            "registerMyInterface"->{platform.registerInterface(generation,args!![0] as String);null}
            else->throw IOException("ANDROID_PLATFORM_UNSUPPORTED_${method.name}")
        }
    } as PlatformInterface
    fun close() {monitor=null;platform.closeDefaultMonitor(generation)}
}
