package org.mcrf.client

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Source-only resolver socket leases. Never connect a socket before the
 * private native ACK. A tuple is additionally checked against kernel UID by BS.
 * send must be the nonblocking bounded carrier writer, not a raw pipe write. */
internal class BsNativeServiceFlowChannel(
    private val generation:Long,private val live:()->Boolean,private val now:()->Long,
    private val send:(ByteArray)->Boolean
):Closeable {
    private class Pending {val ready=CountDownLatch(1);var accepted=false}
    private val lock=Any();private val pending=hashMapOf<Long,Pending>();private val leases=hashSetOf<Long>()
    private var serial=0L;private var closed=false
    init {require(generation>0)}
    private fun command(value:String)=send((value+"\n").toByteArray(Charsets.US_ASCII))
    fun register(protocol:Int,localPort:Int,peer:InetSocketAddress,deadline:Long):Closeable {
        require(protocol in setOf(6,17)&&localPort in 1..65535&&!peer.isUnresolved&&peer.port in 1..65535)
        require(!peer.address.isAnyLocalAddress&&!peer.address.isMulticastAddress&&'%' !in peer.address.hostAddress.orEmpty())
        val started=now();val duration=deadline-started;require(started>=0&&duration in 1..30000)
        val request=Pending()
        val id=synchronized(lock){
            check(!closed&&live()&&pending.size+leases.size<64)
            check(serial<Long.MAX_VALUE);val next=++serial;pending[next]=request
            if(!command("BS_SERVICE_ADD|$generation|$next|$protocol|$localPort|${peer.address.hostAddress}|${peer.port}|$duration")){
                pending.remove(next);throw IOException("BS_DNS_CONTROL_UNAVAILABLE")
            };next
        }
        var granted=false
        try {
            val wait=minOf(2000L,deadline-now());if(wait<=0||!request.ready.await(wait,TimeUnit.MILLISECONDS))throw IOException("BS_DNS_LEASE_TIMEOUT")
            synchronized(lock){
                if(closed||!live()||!request.accepted||now()>=deadline)throw IOException("BS_DNS_LEASE_REJECTED")
                check(pending.remove(id)===request);leases+=id;granted=true
            }
            return Closeable{synchronized(lock){if(leases.remove(id)&&!closed&&!command("BS_SERVICE_DEL|$generation|$id"))retire()}}
        }finally{
            if(!granted)synchronized(lock){pending.remove(id);if(!closed&&!command("BS_SERVICE_DEL|$generation|$id"))retire()}
        }
    }
    /** Called by the single carrier reader; no blocking wait on that thread. */
    fun acknowledge(line:String):Boolean {
        if(!line.startsWith("MCRF_BS_SERVICE "))return false
        val fields=line.removePrefix("MCRF_BS_SERVICE ").split(' ')
        require(fields.size==3&&fields[0]==generation.toString()&&fields[1].matches(Regex("[1-9][0-9]{0,18}"))&&fields[2] in setOf("OK","DENIED"))
        val id=requireNotNull(fields[1].toLongOrNull())
        synchronized(lock){pending[id]?.let{require(it.ready.count>0);it.accepted=fields[2]=="OK";it.ready.countDown()}}
        return true
    }
    private fun retire(){closed=true;pending.values.forEach{it.ready.countDown()};pending.clear();leases.clear()}
    override fun close(){synchronized(lock){if(!closed){(pending.keys+leases).forEach{command("BS_SERVICE_DEL|$generation|$it")};retire()}}}
    override fun toString()="BsNativeServiceFlowChannel(redacted)"
}
