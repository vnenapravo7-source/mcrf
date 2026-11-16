package org.mcrf.client

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Source-only Main-owned DNS listener. The resolver callback MUST apply the
 * captured full DNS plan and admit domain/fake-IP metadata before returning.
 * A started listener alone is not permission to enable native BS routing. */
internal class BsNativeDnsListener(
    private val manager:ConnectivityManager,private val network:Network,
    addresses:List<InetAddress>,private val mtu:Int,private val live:()->Boolean,
    private val mappings:BsNativeDnsClientChannel,
    private val owner:(BsNativeDnsClientChannel.Client)->BsNativeOwnerChannel.Owner,
    private val resolve:(ByteArray,Caller,Long)->ByteArray,
    private val failed:(Failure)->Unit
):Closeable {
    enum class Failure{LISTENER,QUEUE_FULL,QUERY}
    class Caller internal constructor(val client:BsNativeDnsClientChannel.Client,val uid:Int,packages:List<String>){
        val packages:List<String> = java.util.Collections.unmodifiableList(packages.toList())
        override fun toString()="BsNativeDnsCaller(redacted)"
    }
    private val sourceAddresses=addresses.toList()
    private val closed=AtomicBoolean();private val started=AtomicBoolean();private val lock=Any()
    private val fds=hashSetOf<Closeable>();private val listeners=mutableListOf<Thread>()
    private val tcp=mutableListOf<ServerSocket>();private val udp=mutableListOf<DatagramSocket>()
    private val workers=ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(16),{Thread(it,"mcrf-bs-dns-query").apply{isDaemon=true}})
    private val timers=ScheduledThreadPoolExecutor(1){Thread(it,"mcrf-bs-dns-deadline").apply{isDaemon=true}}.apply{removeOnCancelPolicy=true}
    val port:Int
    init{
        require(sourceAddresses.size in 1..16&&sourceAddresses.distinct().size==sourceAddresses.size&&mtu in 1280..9000)
        require(sourceAddresses.none{it.isAnyLocalAddress||it.isLoopbackAddress||it.isMulticastAddress||it.hostAddress.orEmpty() in setOf("198.19.255.53","fdfe:dcba:9876:dd54::53")})
        var selected=0
        try{
            checkLive()
            for(local in sourceAddresses.distinctBy{it.address.size}){
                val stream=ServerSocket();track(stream);stream.bind(InetSocketAddress(local,selected),16);if(selected==0)selected=stream.localPort;require(selected>=1024);tcp+=stream
                val datagram=DatagramSocket(null);track(datagram);network.bindSocket(datagram);datagram.bind(InetSocketAddress(local,selected));udp+=datagram
            };port=selected
        }catch(error:Throwable){close();throw IOException("BS_DNS_LISTENER_PREPARE_FAILED")}
    }
    private fun checkLive(){
        if(closed.get()||!live())throw IOException("BS_DNS_SESSION_RETIRED")
        val caps=manager.getNetworkCapabilities(network)?:throw IOException("BS_DNS_VPN_GONE")
        require(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        if(android.os.Build.VERSION.SDK_INT>=30)require(caps.ownerUid==android.os.Process.myUid())
        val actual=manager.getLinkProperties(network)?.linkAddresses?:throw IOException("BS_DNS_VPN_ADDRESSES_GONE")
        require(sourceAddresses.all{expected->actual.any{it.address==expected}})
    }
    private fun track(fd:Closeable){synchronized(lock){checkLive();require(fds.size<96);fds+=fd}}
    private fun discard(fd:Closeable){try{fd.close()}finally{synchronized(lock){fds-=fd}}}
    private fun caller(protocol:Int,address:InetAddress,port:Int):Caller{
        checkLive();val alias=if(address.address.size==4)"198.19.255.53" else "fdfe:dcba:9876:dd54::53"
        require(address==RouteRules.literal(alias))
        val mapped=mappings.query(protocol,port);require(mapped.protocol==protocol&&mapped.destination.address.size==address.address.size&&sourceAddresses.contains(mapped.source))
        val actual=owner(mapped);checkLive();return Caller(mapped,actual.uid,actual.packages)
    }
    private fun verify(original:Caller,port:Int){
        val fresh=caller(original.client.protocol,original.client.destination,port)
        require(fresh.client==original.client&&fresh.uid==original.uid&&fresh.packages.toSet()==original.packages.toSet()){"BS_DNS_CLIENT_CHANGED"}
    }
    fun start(){
        synchronized(lock){
        check(started.compareAndSet(false,true));checkLive()
        tcp.forEach{server->listeners+=Thread({while(!closed.get())try{
            val socket=server.accept();try{track(socket);workers.execute{stream(socket)}}catch(_:Exception){discard(socket);if(!closed.get())failed(Failure.QUEUE_FULL)}
        }catch(_:Exception){if(!closed.get()){try{failed(Failure.LISTENER)}finally{close()}};break}},"mcrf-bs-dns-tcp").apply{isDaemon=true}}
        udp.forEach{server->listeners+=Thread({while(!closed.get())try{
            val packet=DatagramPacket(ByteArray(4097),4097);server.receive(packet);if(packet.length !in 12..4096)continue
            val bytes=packet.data.copyOfRange(packet.offset,packet.offset+packet.length);val address=packet.address;val port=packet.port
            try{workers.execute{datagram(server,address,port,bytes)}}catch(_:RejectedExecutionException){if(!closed.get())failed(Failure.QUEUE_FULL)}
        }catch(_:Exception){if(!closed.get()){try{failed(Failure.LISTENER)}finally{close()}};break}},"mcrf-bs-dns-udp").apply{isDaemon=true}}
        listeners.forEach{it.start()}
        }
    }
    private fun stream(socket:Socket){
        var timeout:ScheduledFuture<*>?=null
        try{
            timeout=timers.schedule({runCatching{socket.close()}},30,TimeUnit.SECONDS)
            val caller=caller(6,socket.inetAddress,socket.port);val input=DataInputStream(socket.getInputStream());val output=DataOutputStream(socket.getOutputStream())
            repeat(16){verify(caller,socket.port);socket.soTimeout=5000
                val size=try{input.readUnsignedShort()}catch(_:EOFException){return};require(size in 12..4096);val query=ByteArray(size);input.readFully(query);PacketDnsWire.question(query)
                val reply=resolve(query,caller,SystemClock.elapsedRealtime()+10000);PacketDnsWire.response(query,reply);verify(caller,socket.port)
                output.writeShort(reply.size);output.write(reply);output.flush()
            }
        }catch(_:Exception){if(!closed.get())failed(Failure.QUERY)}finally{timeout?.cancel(false);discard(socket)}
    }
    private fun datagram(socket:DatagramSocket,address:InetAddress,port:Int,query:ByteArray){
        try{
            val caller=caller(17,address,port);val question=PacketDnsWire.question(query)
            var reply=resolve(query,caller,SystemClock.elapsedRealtime()+10000);PacketDnsWire.response(query,reply);verify(caller,port)
            if(reply.size>minOf(question.udpLimit,mtu-if(address.address.size==4)28 else 48))reply=PacketDnsWire.reply(query,0,truncated=true)
            checkLive();socket.send(DatagramPacket(reply,reply.size,address,port))
        }catch(_:Exception){if(!closed.get())failed(Failure.QUERY)}
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        val closing=synchronized(lock){fds.toList().also{fds.clear()}};closing.forEach{runCatching{it.close()}}
        workers.shutdownNow();timers.shutdownNow()
    }
    fun awaitStopped(timeoutMillis:Long):Boolean{
        require(timeoutMillis in 0..5000);val deadline=SystemClock.elapsedRealtime()+timeoutMillis
        val snapshot=synchronized(lock){listeners.toList()}
        for(thread in snapshot){if(thread!==Thread.currentThread()){val remaining=deadline-SystemClock.elapsedRealtime();if(remaining>0)thread.join(remaining)}}
        val remaining=(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0)
        if(snapshot.any{it.isAlive}||!workers.awaitTermination(remaining,TimeUnit.MILLISECONDS))return false
        return timers.awaitTermination((deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
    }
}
