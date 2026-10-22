package org.mcrf.client

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.mcrf.engine.spi.v1.Platform
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owned, short-lived PRESTART bootstrap on the parent's captured physical
 * network. Never use a protocol child's restricted platform or default-network
 * hostname sockets. Close it before dispatcher native DNS begins using parent.
 * DoQ/H3/custom JSON uses an owned external DNS-only worker, not a protocol
 * downgrade. It is retired before the main dispatcher's DNS/monitor starts. */
internal class ServerBootstrapResolver(private val context:Context,private val policy:ServerBootstrapPolicy,private val parent:Platform,
                                       private val generation:Long,private val networkHandle:()->Long?,private val stopping:()->Boolean):AutoCloseable {
    private val physicalHandle=networkHandle()
    private val closed=AtomicBoolean();private val lock=Any();private val queryLock=Any()
    private val resources=linkedSetOf<Closeable>();private val systemRequests=linkedSetOf<Long>()
    private val serial=AtomicLong(1L shl 62)
    private val timers=ScheduledThreadPoolExecutor(1){Thread(it,"mcrf-server-bootstrap-deadline")}.apply {removeOnCancelPolicy=true}
    private var retirement:OptionalCoreRetirement?=null
    @Volatile private var native:ServerBootstrapCore?=null
    private fun live(){if(closed.get()||stopping()||networkHandle()!=physicalHandle)throw IOException("SERVER_BOOTSTRAP_RETIRED")}
    private fun track(value:Closeable){synchronized(lock){live();require(resources.size<64){"SERVER_BOOTSTRAP_FD_BOUND"};resources.add(value)}}
    private val transport=PacketDnsTransport(
        {socket,direct->require(direct);live();ParcelFileDescriptor.fromSocket(socket).use {check(parent.protectAndBind(generation,it.fd)){"SERVER_BOOTSTRAP_PROTECT"}};live()},
        {socket,direct->require(direct);live();ParcelFileDescriptor.fromDatagramSocket(socket).use {check(parent.protectAndBind(generation,it.fd)){"SERVER_BOOTSTRAP_PROTECT"}};live()},
        {query,timeout->
            val id=serial.incrementAndGet();require(id>0)
            synchronized(lock){live();systemRequests.add(id)}
            try{val answer=parent.exchangeDns(generation,Platform.DnsQuery(id,query,timeout));live();require(answer.errno==0){"SERVER_BOOTSTRAP_SYSTEM_FAILED"};answer.packet()}
            finally{synchronized(lock){systemRequests.remove(id)};parent.cancelDns(generation,id)}
        },{false},SystemClock::elapsedRealtime,::live,::track,
        { /* Retain bounded Closeable references until the owner verifies cleanup. */ })
    init {
        require(generation>0&&physicalHandle!=null&&physicalHandle>0&&parent !is NativeRoutePlatforms.Child&&parent is ClientPrimaryPlatformLifetime){"SERVER_BOOTSTRAP_PARENT"}
        (parent as ClientPrimaryPlatformLifetime).onClose {requestStop()}
    }
    fun ipv4(host:String):String=synchronized(queryLock){
        live()
        val scope=transport.Scope(SystemClock.elapsedRealtime()+8000)
        val alarm=timers.schedule({scope.close();native?.requestStop()},8000,TimeUnit.MILLISECONDS)
        try{policy.ipv4(host){endpoint,query->
            if(policy.requiresCore){
                val owner=native?:ServerBootstrapCore(context,policy,parent,generation,::live).also {
                    synchronized(lock){live();native=it}
                }
                owner.exchange(query,scope)
            }else {
                val addresses=if(endpoint.type=="system")emptyList()else listOf(requireNotNull(RouteRules.literal(endpoint.server)))
                transport.exchange(endpoint,query,true,addresses,scope)
            }
        }.also {scope.remaining();live()}}
        finally{alarm.cancel(false);scope.close()}
    }
    fun requestStop(){
        closed.set(true);timers.shutdownNow();native?.requestStop()
        val pending=synchronized(lock){systemRequests.toList() to resources.toList()}
        pending.first.forEach {try{parent.cancelDns(generation,it)}catch(_:Exception){}}
        pending.second.forEach {try{it.close()}catch(_:Exception){}}
    }
    override fun close(){
        requestStop()
        synchronized(queryLock){
            val owner=synchronized(lock){retirement?:OptionalCoreRetirement(resources.toList()).also {retirement=it}}
            native?.close()
            owner.close()
            check(timers.awaitTermination(1,TimeUnit.SECONDS)){"SERVER_BOOTSTRAP_TIMER_CLEANUP_PENDING"}
        }
    }
}
