package org.mcrf.client

import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stable authenticated loopback SOCKS endpoint. Switching changes NEW TCP/UDP associations only;
 * old streams keep their exact carrier lease. No second TUN, selector-wide reset or forced drain.
 * NIO gives bounded buffers/workers instead of a pair of threads per website connection.
 */
class CarrierSocksSwitch internal constructor(initialId:String,initial:Endpoint):AutoCloseable {
    internal class Endpoint(val port:Int,val username:String,val password:String,val native:NativeCarrier?,val retire:()->Unit){
        fun close()=retire()
        companion object {fun native(value:NativeCarrier)=Endpoint(value.port,value.username,value.password,value,value::close)}
    }
    constructor(initialId:String,initial:NativeCarrier):this(initialId,Endpoint.native(initial))
    private class Lease(val id:String,val carrier:Endpoint){val flows=AtomicInteger()}
    private val lock=Any()
    private val leases=mutableListOf(Lease(initialId,initial))
    private var selected=leases.single()
    private val closed=AtomicBoolean()
    val username=UUID.randomUUID().toString()
    val password=UUID.randomUUID().toString()
    private val selector=Selector.open()
    private val server=ServerSocketChannel.open().apply {
        bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127,0,0,1)),0))
        configureBlocking(false);register(selector,SelectionKey.OP_ACCEPT)
    }
    val port get()=(server.localAddress as InetSocketAddress).port
    private val accepted=ConcurrentHashMap.newKeySet<SocketChannel>()
    private val flows=ConcurrentHashMap.newKeySet<Flow>()
    private val pending=ConcurrentLinkedQueue<Flow>()
    private val handshakes=ThreadPoolExecutor(2,8,30,TimeUnit.SECONDS,ArrayBlockingQueue(32))
    private val relay=Executors.newSingleThreadExecutor()
    private class End(val channel:SocketChannel){
        val buffer=ByteBuffer.allocate(16384)
        var eof=false
        var outputClosed=false
        lateinit var other:End
        lateinit var flow:Flow
    }
    private class Flow(val client:End,val upstream:End,val lease:Lease){
        val closed=AtomicBoolean()
        init{client.other=upstream;upstream.other=client;client.flow=this;upstream.flow=this}
    }
    init{relay.execute {loop()}}
    fun selectedId()=synchronized(lock){selected.id}
    /** At most one active + two draining native processes. Defer, never evict live streams. */
    fun canAdmit()=synchronized(lock){!closed.get()&&leases.size<3}
    fun select(id:String,carrier:NativeCarrier)=selectEndpoint(id,Endpoint.native(carrier)){carrier.promoteForActive()}
    internal fun selectEndpoint(id:String,carrier:Endpoint,activate:()->Unit={}){
        synchronized(lock){
            check(!closed.get()&&leases.size<3)
            require(leases.none {it.id==id}) {"Этот транспорт ещё обслуживает старые соединения."}
            activate()
            val lease=Lease(id,carrier);leases.add(lease);selected=lease
        }
    }
    fun contains(id:String)=synchronized(lock){leases.any {it.id==id}}
    fun carrierFor(id:String):NativeCarrier?=synchronized(lock){leases.firstOrNull {it.id==id}?.carrier?.native}
    fun selectExisting(id:String)=synchronized(lock){check(!closed.get());selected=leases.single {it.id==id}}
    /** Only the service's serialized owner calls retirement; the NIO thread never stops processes. */
    fun retireDrained(){
        val idle=synchronized(lock){leases.filter {it!==selected&&it.flows.get()==0}}
        for(lease in idle){
            lease.carrier.close() // Actual child retirement barrier, not a STOP acknowledgement.
            synchronized(lock){leases.remove(lease)}
        }
    }
    private fun acquire():Lease=synchronized(lock){
        check(!closed.get());selected.also {it.flows.incrementAndGet()}
    }
    private fun closeFlow(flow:Flow){
        if(!flow.closed.compareAndSet(false,true))return
        runCatching {flow.client.channel.close()};runCatching {flow.upstream.channel.close()}
        accepted.remove(flow.client.channel);flows.remove(flow);flow.lease.flows.decrementAndGet()
    }
    private fun prepare(client:SocketChannel){
        val peer=java.util.concurrent.atomic.AtomicReference<SocketChannel?>()
        val expiry=probeClock.schedule({
            runCatching {client.close()};runCatching {peer.get()?.close()};Unit
        },5000,TimeUnit.MILLISECONDS)
        var lease:Lease?=null
        var upstream:SocketChannel?=null
        var transferred=false
        try{
            client.configureBlocking(true);client.socket().soTimeout=5000
            val input=DataInputStream(client.socket().getInputStream())
            val output=client.socket().getOutputStream()
            require(input.readUnsignedByte()==5)
            val methods=ByteArray(input.readUnsignedByte()).also(input::readFully)
            if(2.toByte() !in methods){output.write(byteArrayOf(5,-1));return}
            output.write(byteArrayOf(5,2))
            require(input.readUnsignedByte()==1)
            fun credential()=ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
            val user=credential();val pass=credential()
            if(user!=username||pass!=password){output.write(byteArrayOf(1,1));return}
            output.write(byteArrayOf(1,0))
            val header=ByteArray(4).also(input::readFully)
            require(header[0].toInt()==5&&header[2].toInt()==0&&header[1].toInt() in setOf(1,3))
            val request=header+address(input,header[3].toInt())+ByteArray(2).also(input::readFully)
            lease=acquire()
            val remote=SocketChannel.open();upstream=remote;peer.set(remote)
            remote.socket().connect(InetSocketAddress("127.0.0.1",lease.carrier.port),2500)
            remote.socket().soTimeout=5000
            val reader=DataInputStream(remote.socket().getInputStream());val writer=remote.socket().getOutputStream()
            authenticate(reader,writer,lease.carrier.username,lease.carrier.password)
            writer.write(request)
            val reply=ByteArray(4).also(reader::readFully)
            require(reply[0].toInt()==5&&reply[2].toInt()==0)
            output.write(reply+address(reader,reply[3].toInt())+ByteArray(2).also(reader::readFully))
            if(reply[1].toInt()!=0)return
            client.socket().soTimeout=0;remote.socket().soTimeout=0
            client.configureBlocking(false);remote.configureBlocking(false)
            val flow=Flow(End(client),End(remote),lease)
            synchronized(lock){
                check(!closed.get()&&flows.size<512)
                flows.add(flow);pending.add(flow);transferred=true
            }
            selector.wakeup()
        }catch(_:Exception){/* No addresses/credentials/configs in diagnostics. */}
        finally{expiry.cancel(false);if(!transferred){
            accepted.remove(client);runCatching {client.close()};runCatching {upstream?.close()}
            lease?.flows?.decrementAndGet()
        }}
    }
    private fun interests(end:End){
        val key=end.channel.keyFor(selector)?:return
        if(!key.isValid)return
        if(!end.outputClosed&&end.other.eof&&end.buffer.position()==0){
            end.channel.shutdownOutput();end.outputClosed=true
        }
        var operations=0
        if(!end.eof&&end.other.buffer.hasRemaining())operations=operations or SelectionKey.OP_READ
        if(end.buffer.position()>0)operations=operations or SelectionKey.OP_WRITE
        key.interestOps(operations)
        if(end.eof&&end.other.eof&&end.buffer.position()==0&&end.other.buffer.position()==0)closeFlow(end.flow)
    }
    private fun loop(){
        try{while(!closed.get()){
            while(true){
                val flow=pending.poll()?:break
                if(flow.closed.get())continue
                try{
                    flow.client.channel.register(selector,SelectionKey.OP_READ,flow.client)
                    flow.upstream.channel.register(selector,SelectionKey.OP_READ,flow.upstream)
                }catch(_:Exception){closeFlow(flow)}
            }
            selector.select(1000)
            val iterator=selector.selectedKeys().iterator()
            while(iterator.hasNext()){
                val key=iterator.next();iterator.remove()
                if(!key.isValid)continue
                if(key.isAcceptable){
                    val client=server.accept()?:continue
                    if(accepted.size>=512){client.close();continue}
                    accepted.add(client)
                    try{handshakes.execute {prepare(client)}}catch(_:Exception){accepted.remove(client);client.close()}
                    continue
                }
                val end=key.attachment() as End
                try{
                    if(key.isReadable&&end.channel.read(end.other.buffer)<0)end.eof=true
                    if(key.isValid&&key.isWritable){end.buffer.flip();end.channel.write(end.buffer);end.buffer.compact()}
                    interests(end);interests(end.other)
                }catch(_:Exception){closeFlow(end.flow)}
            }
        }}finally{
            synchronized(lock){closed.set(true)}
            runCatching {server.close()}
            flows.toList().forEach(::closeFlow)
            accepted.toList().forEach {runCatching {it.close()}}
            runCatching {selector.close()}
        }
    }
    override fun close(){
        synchronized(lock){closed.set(true)}
        runCatching {server.close()};runCatching {selector.wakeup()}
        accepted.toList().forEach {runCatching {it.close()}}
        flows.toList().forEach(::closeFlow)
        handshakes.shutdown();relay.shutdown()
        check(handshakes.awaitTermination(1,TimeUnit.SECONDS)&&relay.awaitTermination(1,TimeUnit.SECONDS)) {"SOCKS-переключатель ещё завершает операции."}
        val owned=synchronized(lock){leases.toList()}
        owned.forEach {it.carrier.close()}
        synchronized(lock){leases.clear()}
    }
    companion object{
        private val probeClock=java.util.concurrent.ScheduledThreadPoolExecutor(1) {task->
            Thread(task,"mcrf-carrier-probe-deadline").apply {isDaemon=true}
        }.apply {removeOnCancelPolicy=true}
        /** Candidate preflight goes through that carrier's authenticated SOCKS, never directly. */
        fun probe(carrier:NativeCarrier,url:String,timeout:Int):Long=try{
            val uri=java.net.URI(url);require(uri.scheme=="https"&&uri.host!=null&&uri.userInfo==null&&url.length<=4096)
            val began=System.nanoTime()
            java.net.Socket().use {raw->
                val expiry=probeClock.schedule({runCatching {raw.close()};Unit},timeout.toLong(),TimeUnit.MILLISECONDS)
                try{
                raw.connect(InetSocketAddress("127.0.0.1",carrier.port),timeout);raw.soTimeout=timeout
                val input=DataInputStream(raw.getInputStream());val output=raw.getOutputStream()
                authenticate(input,output,carrier.username,carrier.password)
                val host=uri.host.toByteArray(Charsets.US_ASCII);require(host.size in 1..255)
                val remotePort=if(uri.port<0)443 else uri.port;require(remotePort in 1..65535)
                output.write(byteArrayOf(5,1,0,3,host.size.toByte())+host+byteArrayOf((remotePort ushr 8).toByte(),remotePort.toByte()))
                val reply=ByteArray(4).also(input::readFully)
                require(reply[0].toInt()==5&&reply[1].toInt()==0&&reply[2].toInt()==0)
                address(input,reply[3].toInt());input.readUnsignedShort()
                val ssl=(javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                    .createSocket(raw,uri.host,remotePort,true) as javax.net.ssl.SSLSocket
                ssl.use {
                    it.soTimeout=timeout
                    it.sslParameters=it.sslParameters.apply {endpointIdentificationAlgorithm="HTTPS"}
                    it.startHandshake()
                    val path=uri.rawPath.orEmpty().ifBlank {"/"}+(uri.rawQuery?.let {query->"?"+query}?:"")
                    require(path.none {c->c=='\r'||c=='\n'})
                    it.outputStream.write(("HEAD "+path+" HTTP/1.1\r\nHost: "+uri.host+"\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    val line=StringBuilder()
                    var ended=false
                    while(line.length<512){
                        val next=it.inputStream.read();require(next>=0)
                        if(next==10){ended=true;break}
                        line.append(next.toChar())
                    }
                    require(ended)
                    val code=Regex("^HTTP/[0-9.]+ ([0-9]{3})(?: |\\r|$)").find(line.toString())?.groupValues?.get(1)?.toIntOrNull()
                    require(code!=null&&code in 100..599)
                }
                }finally{expiry.cancel(false)}
            }
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)
        }catch(_:Exception){-1L}
        private fun address(input:DataInputStream,type:Int):ByteArray=when(type){
            1->ByteArray(4).also(input::readFully)
            4->ByteArray(16).also(input::readFully)
            3->{val count=input.readUnsignedByte();require(count>0);byteArrayOf(count.toByte())+ByteArray(count).also(input::readFully)}
            else->throw IOException("SOCKS_ADDRESS")
        }
        private fun authenticate(input:DataInputStream,output:java.io.OutputStream,user:String,password:String){
            output.write(byteArrayOf(5,1,2));require(input.readUnsignedByte()==5&&input.readUnsignedByte()==2)
            val u=user.toByteArray(Charsets.UTF_8);val p=password.toByteArray(Charsets.UTF_8)
            require(u.size in 1..255&&p.size in 1..255)
            output.write(byteArrayOf(1,u.size.toByte())+u+byteArrayOf(p.size.toByte())+p)
            require(input.readUnsignedByte()==1&&input.readUnsignedByte()==0)
        }
    }
}
