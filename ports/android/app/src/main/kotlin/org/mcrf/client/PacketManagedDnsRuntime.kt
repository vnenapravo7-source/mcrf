package org.mcrf.client

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.mcrf.engine.protocol.dns.v1.ManagedDnsPacketBackend
import org.mcrf.engine.spi.v1.Platform
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Worker-owned managed DNS. No separate VPN/engine, root, local53 listener,
 * app-exclusion workaround or public/system fallback. The optional external WG
 * adapter reflects only internal DNS aliases to these high-port sockets; both
 * kernel TCP endpoints remain ordinary sockets. Fresh original owner checks
 * precede each DNS decision AND reply. STOP closes every listener/control FD.
 */
class PacketManagedDnsRuntime(
    private val manager:ConnectivityManager,private val network:Network,private val generation:Long,
    private val original:CompactWireGuardPlan.Plan,private val captured:PacketRouteEnvelope.Captured,
    private val platform:Platform,private val backend:ManagedDnsPacketBackend,private val stopped:()->Boolean
):Closeable {
    private val plan=requireNotNull(captured.managed)
    private val closed=AtomicBoolean();private val started=AtomicBoolean()
    private val lock=Any();private val fds=hashSetOf<Closeable>()
    private val workers=ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(16),{Thread(it,"mcrf-wg-dns-query")})
    private val timers=ScheduledThreadPoolExecutor(1){Thread(it,"mcrf-wg-dns-deadline")}.apply {removeOnCancelPolicy=true}
    private val listeners=mutableListOf<Thread>();private val tcp=mutableListOf<ServerSocket>();private val udp=mutableListOf<DatagramSocket>()
    private val systemRequests=ConcurrentHashMap.newKeySet<Long>();private val serial=AtomicLong(System.nanoTime().and(Long.MAX_VALUE).coerceAtLeast(1))
    private class Cached(val reply:PacketDnsWire.Reply,val created:Long,val weight:Int)
    private val cacheLock=Any();private val cache=LinkedHashMap<String,Cached>(16,.75f,true);private var cacheBytes=0
    private val hosts=plan.hosts()
    private val transport=PacketDnsTransport(
        {socket,direct->checkLive();if(direct)ParcelFileDescriptor.fromSocket(socket).use {require(platform.protectAndBind(generation,it.fd)){"PACKET_DNS_PROTECT_REJECTED"}} else network.bindSocket(socket);checkLive()},
        {socket,direct->checkLive();if(direct)ParcelFileDescriptor.fromDatagramSocket(socket).use {require(platform.protectAndBind(generation,it.fd)){"PACKET_DNS_PROTECT_REJECTED"}} else network.bindSocket(socket);checkLive()},
        {query,timeout->val id=serial.incrementAndGet();require(id>0);systemRequests+=id
            try{checkLive();val answer=platform.exchangeDns(generation,Platform.DnsQuery(id,query,timeout));checkLive();require(answer.errno==0){"PACKET_DNS_SYSTEM_FAILED"};answer.packet()}
            finally{systemRequests-=id;platform.cancelDns(generation,id)}},
        {address->admitted(address)},SystemClock::elapsedRealtime,::checkLive,::track,::untrack)
    val config:ManagedDnsPacketBackend.Config

    init {
        PacketManagedDnsPlan.validate(plan,original,captured.projection)
        val addresses=original.addresses.map {requireNotNull(RouteRules.literal(it.substringBefore('/')))}
        require(addresses.size in 1..16){"PACKET_DNS_SOURCE_BOUND"}
        val first4=addresses.firstOrNull {it.address.size==4};val first6=addresses.firstOrNull {it.address.size==16}
        var port=0
        try{
            for(address in listOfNotNull(first4,first6)){
                val stream=ServerSocket();track(stream);stream.reuseAddress=false;stream.bind(InetSocketAddress(address,port),16)
                if(port==0)port=stream.localPort;require(port in 1024..65535);tcp+=stream
                val datagram=DatagramSocket(null);track(datagram);datagram.reuseAddress=false;network.bindSocket(datagram);datagram.bind(InetSocketAddress(address,port));udp+=datagram
            }
            config=ManagedDnsPacketBackend.Config(first4?.hostAddress.orEmpty(),first6?.hostAddress.orEmpty(),addresses.joinToString(","){requireNotNull(it.hostAddress)},port)
        }catch(error:Throwable){close();throw IOException("PACKET_DNS_LISTENER_FAILED")}
    }
    private fun track(fd:Closeable){synchronized(lock){checkLive();require(fds.size<96){"PACKET_DNS_FD_BOUND"};fds+=fd}}
    private fun untrack(fd:Closeable){synchronized(lock){fds-=fd}}
    private fun checkLive(){
        if(closed.get()||stopped())throw IOException("PACKET_DNS_RETIRED")
        val caps=manager.getNetworkCapabilities(network)?:throw IOException("PACKET_DNS_VPN_GONE")
        require(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&caps.ownerUid==android.os.Process.myUid()){"PACKET_DNS_OWNED_VPN_REQUIRED"}
    }
    fun start(){
        check(started.compareAndSet(false,true));checkLive()
        for(server in tcp)listeners+=Thread({while(!closed.get())try{
            val socket=server.accept();try{track(socket);socket.soTimeout=5000;workers.execute {handleTcp(socket)}}catch(_:Exception){socket.close();untrack(socket)}
        }catch(_:Exception){if(!closed.get())close();break}},"mcrf-wg-dns-tcp").apply {start()}
        for(server in udp)listeners+=Thread({while(!closed.get())try{
            val packet=DatagramPacket(ByteArray(4097),4097);server.receive(packet)
            if(packet.length !in 12..4096)continue
            val query=packet.data.copyOfRange(packet.offset,packet.offset+packet.length);val address=packet.address;val port=packet.port
            try{workers.execute {handleUdp(server,address,port,query)}}catch(_:RuntimeException){}
        }catch(_:Exception){if(!closed.get())close();break}},"mcrf-wg-dns-udp").apply {start()}
    }
    private class Caller(val mapped:ManagedDnsPacketBackend.Client,val uid:Int,val packages:Set<String>,val remote:InetAddress,val port:Int)
    private fun caller(protocol:Int,address:InetAddress,port:Int):Caller {
        checkLive();val expected=if(address.address.size==4)ManagedDnsPacketBackend.ALIAS4 else ManagedDnsPacketBackend.ALIAS6
        require(address==RouteRules.literal(expected)){"PACKET_DNS_NON_ALIAS_CLIENT"}
        val mapped=backend.dnsClient(protocol,port)?:throw IOException("PACKET_DNS_UNKNOWN_CLIENT")
        require(mapped.protocol==protocol&&RouteRules.literal(mapped.destination)==address){"PACKET_DNS_CLIENT_FAMILY"}
        val owner=platform.connectionOwner(generation,Platform.ConnectionQuery(protocol,mapped.source,mapped.sourcePort,mapped.destination,53))
        checkLive();return Caller(mapped,owner.uid,owner.packageNames.toSet(),address,port)
    }
    private fun verify(expected:Caller){
        val fresh=caller(expected.mapped.protocol,expected.remote,expected.port)
        require(fresh.uid==expected.uid&&fresh.packages==expected.packages&&fresh.mapped.source==expected.mapped.source&&fresh.mapped.sourcePort==expected.mapped.sourcePort&&fresh.mapped.destination==expected.mapped.destination){"PACKET_DNS_CLIENT_CHANGED"}
    }
    private fun handleTcp(socket:Socket){
        val lifetime=timers.schedule({try{socket.close()}catch(_:Exception){}},30,TimeUnit.SECONDS)
        try{
            val caller=caller(6,socket.inetAddress,socket.port);val input=DataInputStream(socket.getInputStream());val output=DataOutputStream(socket.getOutputStream())
            repeat(16){
                verify(caller);socket.soTimeout=5000
                val size=try{input.readUnsignedShort()}catch(_:java.io.EOFException){return};require(size in 12..4096){"PACKET_DNS_CLIENT_FRAME"}
                val query=ByteArray(size);input.readFully(query);verify(caller)
                val reply=resolve(query,caller);verify(caller);output.writeShort(reply.size);output.write(reply);output.flush()
            }
        }catch(_:Exception){}finally{lifetime.cancel(false);try{socket.close()}finally{untrack(socket)}}
    }
    private fun handleUdp(socket:DatagramSocket,address:InetAddress,port:Int,query:ByteArray){
        try{
            val caller=caller(17,address,port);val q=PacketDnsWire.question(query)
            var reply=resolve(query,caller);verify(caller)
            val maximum=minOf(q.udpLimit,original.mtu-(if(address.address.size==4)28 else 48))
            if(reply.size>maximum)reply=PacketDnsWire.reply(query,0,truncated=true)
            socket.send(DatagramPacket(reply,reply.size,address,port))
        }catch(_:Exception){}
    }
    private fun admitted(address:InetAddress):Boolean {
        if(address.isAnyLocalAddress||address.isMulticastAddress||address==RouteRules.literal(ManagedDnsPacketBackend.ALIAS4)||address==RouteRules.literal(ManagedDnsPacketBackend.ALIAS6))return false
        if(original.addresses.none {ClientTunCidr.parse(it,false).bits==address.address.size*8})return false
        val host=ClientTunCidr.parse("${address.hostAddress}/${address.address.size*8}")
        return captured.projection.peer.any {ClientTunCidr.parse(it).overlaps(host)}
    }
    private fun bootstrap(endpoint:PacketManagedDnsPlan.Endpoint,scope:PacketDnsTransport.Scope):List<InetAddress> {
        if(endpoint.type=="system")return emptyList()
        RouteRules.literal(endpoint.server)?.let {return listOf(it)}
        val order=if(plan.strategy=="prefer_ipv6"||plan.strategy=="ipv6_only"&&!plan.ipv4Only)listOf(28,1)else listOf(1,28)
        val allowed=when {plan.ipv4Only||plan.strategy=="ipv4_only"->setOf(1);plan.strategy=="ipv6_only"->setOf(28);else->setOf(1,28)}
        val found=mutableListOf<InetAddress>()
        for(type in order.filter {it in allowed}){
            val query=PacketDnsWire.query(endpoint.server,type,SecureRandom().nextInt(65536));scope.remaining()
            try{val numbers=if(plan.local.type=="system")emptyList()else listOf(requireNotNull(RouteRules.literal(plan.local.server)))
                val reply=transport.exchange(plan.local,query,true,numbers,scope);found+=PacketDnsWire.addresses(query,reply)
            }catch(_:Exception){scope.remaining()}
        }
        if(found.isEmpty())throw IOException("PACKET_DNS_BOOTSTRAP_FAILED")
        return found.distinct().take(8)
    }
    private fun resolve(query:ByteArray,caller:Caller):ByteArray {
        verify(caller);val q=PacketDnsWire.question(query)
        val scope=transport.Scope(SystemClock.elapsedRealtime()+plan.timeoutMillis)
        val deadline=timers.schedule({scope.close()},plan.timeoutMillis.toLong(),TimeUnit.MILLISECONDS)
        try{
            val noAAAA=plan.ipv4Only||if(plan.finalDirect)plan.directNoAAAA else plan.remoteNoAAAA
            if(q.type==28&&(noAAAA||plan.strategy=="ipv4_only")||q.type==1&&!plan.ipv4Only&&plan.strategy=="ipv6_only")return PacketDnsWire.reply(query,0)
            hosts.optJSONArray(q.name)?.let {records->
                if(q.type !in setOf(1,28))return PacketDnsWire.reply(query,0)
                val family=if(q.type==1)4 else 16
                return PacketDnsWire.reply(query,0,(0 until records.length()).map {requireNotNull(RouteRules.literal(records.getString(it)))}.filter {it.address.size==family})
            }
            val key=PacketDnsWire.cacheKey(query);val now=SystemClock.elapsedRealtime()
            synchronized(cacheLock){cache[key]?.let {old->val elapsed=((now-old.created)/1000).toInt();if(elapsed in 0 until old.reply.cacheSeconds)return PacketDnsWire.cached(old.reply,query,elapsed);cache.remove(key);cacheBytes-=old.weight}}
            val endpoints=if(plan.finalDirect)listOf(plan.direct)else plan.remote
            var answer:ByteArray?=null
            for(endpoint in endpoints){
                scope.remaining();verify(caller)
                try{answer=transport.exchange(endpoint,query,plan.finalDirect,bootstrap(endpoint,scope),scope);break}catch(_:Exception){scope.remaining()}
            }
            val reply=answer?:throw IOException("PACKET_DNS_SELECTED_UPSTREAM_FAILED")
            scope.remaining();verify(caller);val validated=PacketDnsWire.response(query,reply)
            if(plan.cacheCapacity>0&&validated.cacheSeconds>0){
                val weight=reply.size+validated.ttlOffsets.size*16+key.length*2
                if(weight<=8388608)synchronized(cacheLock){
                    cache.remove(key)?.let {cacheBytes-=it.weight};cache[key]=Cached(validated,SystemClock.elapsedRealtime(),weight);cacheBytes+=weight
                    val iterator=cache.entries.iterator();while((cache.size>plan.cacheCapacity||cacheBytes>8388608)&&iterator.hasNext()){cacheBytes-=iterator.next().value.weight;iterator.remove()}
                }
            }
            return reply
        }catch(_:Exception){checkLive();return PacketDnsWire.reply(query,2)}
        finally{deadline.cancel(false);scope.close()}
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        for(id in systemRequests)try{platform.cancelDns(generation,id)}catch(_:Exception){}
        val closing=synchronized(lock){fds.toList().also {fds.clear()}}
        closing.forEach {try{it.close()}catch(_:Exception){}}
        workers.shutdownNow();timers.shutdownNow()
        synchronized(cacheLock){cache.clear();cacheBytes=0}
        // Actual process/worker retirement is still owned by the existing STOP
        // barrier. A closed stream/future alone is never called worker death.
    }
}
