package org.mcrf.client

import java.util.concurrent.atomic.AtomicBoolean

/** Lifecycle presentation only, NOT traffic reachability or cleanup ACK.
 * STOP/death and a late START/listener reply share one publication lock.
 * Status and ports form a single immutable snapshot, never a mixed update. */
internal class PrimaryWorkerPublication {
    data class Snapshot(val status:String,val proxyPort:Int=0,val routePort:Int=0)
    val stopping=AtomicBoolean()
    val retired=AtomicBoolean()
    private val lock=Any()
    @Volatile private var current=Snapshot("BOUND")
    val snapshot:Snapshot get()=current

    fun publish(status:String,proxyPort:Int=0,routePort:Int=0,alive:()->Boolean):Boolean {
        require(status in setOf("READY","STARTING","RUNNING","PACKET_RUNNING","PROBING","ROUTE_LISTENING","PACKET_PIPE_STARTED"))
        require(proxyPort==0||status in setOf("STARTING","RUNNING")&&proxyPort in 1024..65535)
        require(if(status=="ROUTE_LISTENING")routePort in 1024..65535 else routePort==0)
        synchronized(lock) {
            if(stopping.get()||retired.get()||!alive())return false
            current=Snapshot(status,proxyPort,routePort);return true
        }
    }
    fun stop():Boolean=synchronized(lock) {
        if(!stopping.compareAndSet(false,true))return false
        current=Snapshot(if(retired.get())"STOPPED" else "STOPPING");true
    }
    /** Observed native death or rejected pre-INIT ownership, not cleanup done. */
    fun retire():Boolean=synchronized(lock) {
        if(!retired.compareAndSet(false,true))return false
        stopping.set(true);current=Snapshot("STOPPED");true
    }
}
