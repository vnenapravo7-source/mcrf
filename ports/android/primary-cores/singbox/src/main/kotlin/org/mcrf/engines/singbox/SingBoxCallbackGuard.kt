package org.mcrf.engines.singbox

import java.io.IOException

/** Actual callback guards shared by the native adapter and host-only tests; no JNI types. */
class SingBoxCallbackGuard(private val generation: Long,private val stopping: ()->Boolean,
                           private val prepare: (Int)->Boolean) {
    private val tunRequested=java.util.concurrent.atomic.AtomicBoolean()
    init {require(generation>0)}
    fun current() {if(stopping())throw IOException("CORE_GENERATION_STOPPED")}
    fun protect(fd: Int) {
        current();require(fd>=0) {"SOCKET_FD"}
        if(!prepare(fd))throw IOException("SOCKET_PREPARE_DENIED")
        current() // STOP racing the host ACK must not permit a new native connect.
    }
    fun <T> tun(mode: String,open: ()->T): T {
        current();require(mode=="vpn") {"PROXY_MODE_TUN_REFUSED"}
        require(tunRequested.compareAndSet(false,true)) {"CORE_TUN_ALREADY_REQUESTED"}
        val result=open();current();return result
    }
    fun event(eventGeneration: Long,isCurrentListener: Boolean,deliver: ()->Unit) {
        if(eventGeneration==generation && isCurrentListener && !stopping())deliver()
    }
}
