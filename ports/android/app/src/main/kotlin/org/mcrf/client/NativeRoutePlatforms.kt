package org.mcrf.client

import org.mcrf.engine.spi.v1.Platform
import java.io.IOException

/** A single parent owns physical monitoring and the ONLY TUN. Calling forRun
 * for each backend would retire the preceding run: do not do that. These
 * children can only protect/bind their endpoint sockets. They cannot create a
 * TUN, DNS query, default monitor, notification or original-owner lookup, and
 * closing one must never close the parent or another server's platform. */
internal class NativeRoutePlatforms(private val parent:Platform,private val generation:Long,
                                    private val stopping:()->Boolean):AutoCloseable {
    private val lock=Any();private var closed=false
    private val children=linkedMapOf<Int,Child>()
    private var retirement:OptionalCoreRetirement?=null
    init {
        require(generation>0&&parent is ClientPrimaryPlatformLifetime){"NATIVE_ROUTE_PARENT_LIFETIME"}
        // One parent hook for the whole bounded set, not one per worker.
        (parent as ClientPrimaryPlatformLifetime).onClose {close()}
    }
    fun child(slot:Int):Child=synchronized(lock){
        require(slot in 0..7){"NATIVE_ROUTE_SLOT"}
        check(!closed&&!stopping()&&slot !in children){"NATIVE_ROUTE_PLATFORM_RETIRED"}
        Child().also {children[slot]=it}
    }
    override fun close(){
        val owner=synchronized(lock){closed=true;retirement?:OptionalCoreRetirement(children.values.toList()).also {retirement=it}}
        owner.close()
    }
    inner class Child internal constructor():Platform,ClientPrimaryPlatformLifetime {
        private val childLock=Any();private var retired=false
        private val actions=mutableListOf<()->Unit>()
        private var retirement:OptionalCoreRetirement?=null
        private fun live(gen:Long)=gen==generation&&!retired&&!synchronized(lock){closed}&&!stopping()
        private fun denied(gen:Long):Nothing {if(gen!=generation)throw IOException("NATIVE_ROUTE_GENERATION");throw IOException("NATIVE_ROUTE_PROTOCOL_ONLY")}
        override fun onClose(action:()->Unit){
            val invoke=synchronized(childLock){if(retired)true else {check(actions.size<8);actions+=action;false}}
            if(invoke)action()
        }
        override fun close(){
            val owner=synchronized(childLock){
                retired=true
                retirement?:OptionalCoreRetirement(actions.map {action->AutoCloseable {action()}}).also {retirement=it;actions.clear()}
            }
            owner.close() // Failed callbacks remain reachable; no false ACK.
        }
        override fun protectAndBind(generation:Long,borrowedSocketFd:Int):Boolean=synchronized(childLock){
            if(borrowedSocketFd<0||!live(generation))false
            else parent.protectAndBind(generation,borrowedSocketFd)&&live(generation)
        }
        override fun features(generation:Long):Platform.Features=synchronized(childLock){
            if(!live(generation))throw IOException("NATIVE_ROUTE_PLATFORM_RETIRED")
            Platform.Features(true,false,false,false,Platform.Availability.UNSUPPORTED,Platform.Availability.UNSUPPORTED,
                Platform.Availability.UNSUPPORTED,Platform.Availability.UNSUPPORTED,Platform.Availability.UNSUPPORTED,Platform.Availability.UNSUPPORTED)
        }
        override fun openTun(generation:Long,plan:Platform.TunPlan):Int=denied(generation)
        override fun interfaces(generation:Long):List<Platform.InterfaceInfo> = denied(generation)
        override fun connectionOwner(generation:Long,query:Platform.ConnectionQuery):Platform.ConnectionOwner=denied(generation)
        override fun exchangeDns(generation:Long,query:Platform.DnsQuery):Platform.DnsReply=denied(generation)
        override fun cancelDns(generation:Long,requestId:Long){ /* Never admitted, never cancel a parent's DNS job. */ }
        override fun startDefaultMonitor(generation:Long,listener:Platform.DefaultListener):Unit=denied(generation)
        override fun closeDefaultMonitor(generation:Long){ /* Never close the parent's monitor. */ }
        override fun wifiState(generation:Long):Platform.WifiState=denied(generation)
        override fun clearDnsCache(generation:Long):Unit=denied(generation)
        override fun notification(generation:Long,notice:Platform.Notice):Unit=denied(generation)
        override fun cancelNotification(generation:Long,id:String,type:Int){ /* Never touches parent/user notifications. */ }
        override fun startNeighborMonitor(generation:Long,listener:Platform.NeighborListener):Unit=denied(generation)
        override fun closeNeighborMonitor(generation:Long){ }
        override fun registerInterface(generation:Long,name:String):Unit=denied(generation)
        override fun hostname(generation:Long):String=denied(generation)
        override fun traffic(generation:Long,txTotal:Long,rxTotal:Long){ /* Only dispatcher publishes session counters. */ }
    }
}
