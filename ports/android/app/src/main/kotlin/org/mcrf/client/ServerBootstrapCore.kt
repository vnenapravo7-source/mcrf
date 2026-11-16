package org.mcrf.client

import android.content.Context
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.engine.spi.v1.Platform
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** PRESTART only: exact native DNS transports in the signed external module.
 * No saved profile changes, TUN, user listener, or dependency on the not-yet
 * running server. The private authenticated ingress accepts DNS only. */
internal class ServerBootstrapCore(private val context:Context,private val policy:ServerBootstrapPolicy,
                                   parent:Platform,private val generation:Long,private val checkParent:()->Unit):Closeable {
    private val stopped=AtomicBoolean()
    private val lock=Any()
    @Volatile private var worker:ClientPrimaryWorker?=null
    private val user=UUID.randomUUID().toString();private val password=UUID.randomUUID().toString()
    private var port=0
    private fun live(){checkParent();if(stopped.get())throw IOException("SERVER_DNS_CORE_RETIRED")}
    private val platform=BootstrapPlatform(parent,generation,::live)

    private fun start(scope:PacketDnsTransport.Scope) {
        live();scope.remaining()
        if(worker!=null){check(worker?.state=="RUNNING"){"SERVER_DNS_CORE_NOT_RUNNING"};return}
        if(EnginePackages.missing(context,setOf("sing-box")).isNotEmpty())throw ClientOptionalCoreRequired("sing-box")
        port=ServerSocket(0,1,InetAddress.getByAddress(byteArrayOf(127,0,0,1))).use {it.localPort}
        val settings=ClientPrimarySelection.envelope(policy.coreConfig(port,user,password),false,false)
        val core=try{ClientPrimaryWorker.connect(context,"singbox",generation,platform,ClientPrimaryBootstrapWorkerService::class.java,
            onDeath={stopped.set(true)})}catch(pending:ClientPrimaryWorker.CleanupPending){worker=pending.worker;throw pending}
        synchronized(lock){worker=core;if(stopped.get())core.requestStop()}
        live();scope.remaining()
        require(core.validate("",settings,Engine.Mode.PROXY).supported){"SERVER_DNS_NATIVE_CONFIG_REJECTED"}
        live();scope.remaining();core.start("",settings,Engine.Mode.PROXY);live();scope.remaining()
    }

    fun exchange(query:ByteArray,scope:PacketDnsTransport.Scope):ByteArray {
        start(scope)
        return scope.owned(Socket()){socket->
            socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127,0,0,1)),port),scope.remaining())
            fun input():DataInputStream {live();socket.soTimeout=scope.remaining();return DataInputStream(socket.getInputStream())}
            val out=DataOutputStream(socket.getOutputStream())
            out.write(byteArrayOf(5,1,2));out.flush()
            require(input().readUnsignedByte()==5&&input().readUnsignedByte()==2){"SERVER_DNS_SOCKS_METHOD"}
            val u=user.toByteArray(Charsets.US_ASCII);val p=password.toByteArray(Charsets.US_ASCII)
            out.writeByte(1);out.writeByte(u.size);out.write(u);out.writeByte(p.size);out.write(p);out.flush()
            require(input().readUnsignedByte()==1&&input().readUnsignedByte()==0){"SERVER_DNS_SOCKS_AUTH"}
            // Documentation-only numeric address. Ingress always hijacks DNS;
            // there is no hostname resolution or connection to this address.
            out.write(byteArrayOf(5,1,0,1,192.toByte(),0,2,1,0,53));out.flush()
            require(input().readUnsignedByte()==5&&input().readUnsignedByte()==0&&input().readUnsignedByte()==0){"SERVER_DNS_SOCKS_CONNECT"}
            val count=when(input().readUnsignedByte()){1->4;4->16;3->input().readUnsignedByte();else->error("SERVER_DNS_SOCKS_ADDRESS")}
            input().readFully(ByteArray(count));input().readUnsignedShort()
            out.writeShort(query.size);out.write(query);out.flush()
            val size=input().readUnsignedShort();require(size in 12..65535){"SERVER_DNS_REPLY_SIZE"}
            ByteArray(size).also {input().readFully(it);live();scope.remaining();PacketDnsWire.response(query,it)}
        }
    }
    fun requestStop(){
        synchronized(lock){stopped.set(true);worker?.requestStop()}
        // Broadcast cancellation even from parent death/timer. Failed cleanup
        // remains in platform's exact retirement owner for close(), not an ACK.
        try{platform.close()}catch(_:Exception){}
    }
    override fun close(){requestStop();worker?.close();platform.close()}

    /** Borrows the physical monitor ONLY before the dispatcher starts. Closing
     * this lifetime never closes the parent's TUN/network owner. DNS IDs are
     * namespaced and cancellation cannot target somebody else's request. */
    private class BootstrapPlatform(private val parent:Platform,private val generation:Long,
                                    private val check:()->Unit):Platform by parent,ClientPrimaryPlatformLifetime {
        private val lock=Any();@Volatile private var closed=false;private var monitor=false
        private var retirement:OptionalCoreRetirement?=null
        private val serial=AtomicLong(1L shl 60)
        private val queries=linkedMapOf<Long,Long>();private val callbacks=mutableListOf<()->Unit>()
        private fun live(gen:Long){check();if(gen!=generation||synchronized(lock){closed})throw IOException("SERVER_DNS_PLATFORM_RETIRED")}
        override fun onClose(action:()->Unit){val invoke=synchronized(lock){if(closed)true else {require(callbacks.size<8);callbacks+=action;false}};if(invoke)action()}
        override fun close(){
            val owner=synchronized(lock){closed=true;retirement?:OptionalCoreRetirement(
                queries.values.map {id->AutoCloseable {parent.cancelDns(generation,id)}}+
                    listOf(AutoCloseable {closeDefaultMonitor(generation)})+callbacks.map {action->AutoCloseable {action()}}
            ).also {retirement=it}}
            owner.close()
        }
        override fun protectAndBind(generation:Long,borrowedSocketFd:Int):Boolean {live(generation);return parent.protectAndBind(generation,borrowedSocketFd).also {live(generation)}}
        override fun interfaces(generation:Long):List<Platform.InterfaceInfo>{live(generation);return parent.interfaces(generation).also {live(generation)}}
        override fun exchangeDns(generation:Long,query:Platform.DnsQuery):Platform.DnsReply {
            val id=synchronized(lock){live(generation);require(queries.size<64&&query.requestId !in queries);serial.incrementAndGet().also {queries[query.requestId]=it}}
            try{return parent.exchangeDns(generation,Platform.DnsQuery(id,query.packet(),query.timeoutMillis)).also {live(generation)}}
            finally{synchronized(lock){queries.remove(query.requestId)};parent.cancelDns(generation,id)}
        }
        override fun cancelDns(generation:Long,requestId:Long){if(generation==this.generation)synchronized(lock){queries.remove(requestId)}?.let {parent.cancelDns(generation,it)}}
        override fun startDefaultMonitor(generation:Long,listener:Platform.DefaultListener){synchronized(lock){live(generation);check(!monitor);monitor=true
            parent.startDefaultMonitor(generation){gen,value->if(!closed)listener.update(gen,value)}}}
        override fun closeDefaultMonitor(generation:Long){synchronized(lock){if(generation==this.generation&&monitor){monitor=false;parent.closeDefaultMonitor(generation)}}}
        override fun features(generation:Long):Platform.Features {live(generation);return Platform.Features(true,false,false,false,
            Platform.Availability.UNSUPPORTED,Platform.Availability.UNSUPPORTED,Platform.Availability.UNSUPPORTED,
            Platform.Availability.UNSUPPORTED,Platform.Availability.UNSUPPORTED,Platform.Availability.UNSUPPORTED)}
        private fun denied():Nothing=throw IOException("SERVER_DNS_PLATFORM_ONLY")
        override fun openTun(generation:Long,plan:Platform.TunPlan):Int=denied()
        override fun connectionOwner(generation:Long,query:Platform.ConnectionQuery):Platform.ConnectionOwner=denied()
        override fun wifiState(generation:Long):Platform.WifiState=denied()
        override fun clearDnsCache(generation:Long):Unit=denied()
        override fun notification(generation:Long,notice:Platform.Notice):Unit=denied()
        override fun cancelNotification(generation:Long,id:String,type:Int){}
        override fun startNeighborMonitor(generation:Long,listener:Platform.NeighborListener):Unit=denied()
        override fun closeNeighborMonitor(generation:Long){}
        override fun registerInterface(generation:Long,name:String):Unit=denied()
        override fun hostname(generation:Long):String=denied()
        override fun traffic(generation:Long,txTotal:Long,rxTotal:Long){}
    }
}
