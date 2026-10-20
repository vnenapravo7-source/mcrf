package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Every socket must first be bound to the explicitly chosen VPN or protected
 * physical network by the owner. Never uses hostname Socket constructors,
 * InetAddress.getByName, URLConnection, platform fallback, TLS bypass or redirect.
 * Its owner closes all tracked FDs on STOP, including bootstrap and TLS handshakes.
 */
class PacketDnsTransport(
    private val prepareTcp:(Socket,Boolean)->Unit,
    private val prepareUdp:(DatagramSocket,Boolean)->Unit,
    private val system:(ByteArray,Int)->ByteArray,
    private val allowVpn:(InetAddress)->Boolean,
    private val now:()->Long,
    private val check:()->Unit,
    private val register:(Closeable)->Unit,
    private val unregister:(Closeable)->Unit
) {
    inner class Scope(val deadline:Long):Closeable {
        private val lock=Any();private val resources=hashSetOf<Closeable>();@Volatile private var closed=false
        fun remaining():Int {check();if(closed)throw IOException("PACKET_DNS_CANCELLED");val remaining=deadline-now();if(remaining<=0)throw IOException("PACKET_DNS_DEADLINE");return remaining.coerceAtMost(30000).toInt()}
        fun <T:Closeable,R> owned(value:T,body:(T)->R):R {
            try{synchronized(lock){if(closed)throw IOException("PACKET_DNS_CANCELLED");resources+=value};register(value);remaining();return body(value)}finally{try{value.close()}finally{synchronized(lock){resources-=value};unregister(value)}}
        }
        override fun close(){val closing=synchronized(lock){closed=true;resources.toList().also {resources.clear()}};closing.forEach {try{it.close()}catch(_:Exception){}}}
    }
    fun exchange(endpoint:PacketManagedDnsPlan.Endpoint,query:ByteArray,direct:Boolean,addresses:List<InetAddress>,scope:Scope):ByteArray {
        scope.remaining();PacketDnsWire.question(query)
        if(endpoint.type=="system"){
            require(direct){"PACKET_DNS_SYSTEM_NOT_VPN"}
            return system(query,scope.remaining().coerceAtMost(8000)).also {scope.remaining();PacketDnsWire.response(query,it)}
        }
        require(addresses.isNotEmpty()&&addresses.size<=8){"PACKET_DNS_BOOTSTRAP_REQUIRED"}
        var failure:Exception?=null
        for(address in addresses){
            scope.remaining()
            if(!direct&&!allowVpn(address))continue
            try{
                val answer=when(endpoint.type){
                    "udp"->udp(endpoint,address,query,direct,scope)
                    "tcp"->stream(endpoint,address,query,direct,scope,false)
                    "tls","https"->stream(endpoint,address,query,direct,scope,true)
                    else->throw IOException("PACKET_DNS_TRANSPORT")
                }
                scope.remaining();val validated=PacketDnsWire.response(query,answer)
                if(validated.truncated){
                    if(endpoint.type!="udp")throw IOException("PACKET_DNS_STREAM_TRUNCATED")
                    val full=stream(endpoint,address,query,direct,scope,false)
                    if(PacketDnsWire.response(query,full).truncated)throw IOException("PACKET_DNS_STREAM_TRUNCATED")
                    return full
                }
                return answer
            }catch(error:Exception){scope.remaining();failure=error}
        }
        // Do NOT include exceptions/URLs/queries/provider names in cross-process errors.
        throw IOException(if(failure==null)"PACKET_DNS_NO_ADMITTED_ADDRESS" else "PACKET_DNS_EXCHANGE_FAILED")
    }
    private fun udp(endpoint:PacketManagedDnsPlan.Endpoint,address:InetAddress,query:ByteArray,direct:Boolean,scope:Scope):ByteArray =
        scope.owned(DatagramSocket(null)){socket->
            prepareUdp(socket,direct);scope.remaining();socket.bind(InetSocketAddress(0));socket.connect(address,endpoint.port)
            socket.soTimeout=scope.remaining().coerceAtMost(2500)
            socket.send(DatagramPacket(query,query.size));scope.remaining()
            val reply=DatagramPacket(ByteArray(65535),65535);socket.receive(reply);scope.remaining()
            require(reply.address==address&&reply.port==endpoint.port){"PACKET_DNS_REPLY_ENDPOINT"}
            reply.data.copyOfRange(reply.offset,reply.offset+reply.length)
        }
    private fun stream(endpoint:PacketManagedDnsPlan.Endpoint,address:InetAddress,query:ByteArray,direct:Boolean,scope:Scope,tls:Boolean):ByteArray =
        scope.owned(Socket()){raw->
            // Materialize the kernel FD before ParcelFileDescriptor.fromSocket
            // and protectAndBind. Binding locally does not connect or send data.
            raw.bind(InetSocketAddress(0));prepareTcp(raw,direct);scope.remaining();raw.connect(InetSocketAddress(address,endpoint.port),scope.remaining().coerceAtMost(3000));raw.soTimeout=scope.remaining()
            if(!tls)framed(raw,query,scope)
            else {
                val secure=(SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,endpoint.tlsName,endpoint.port,true) as SSLSocket
                scope.owned(secure){socket->
                    val parameters=socket.sslParameters
                    parameters.endpointIdentificationAlgorithm="HTTPS"
                    if(RouteRules.literal(endpoint.tlsName)==null)parameters.serverNames=listOf(SNIHostName(endpoint.tlsName))
                    socket.sslParameters=parameters
                    val protocols=socket.supportedProtocols.filter {it=="TLSv1.3"||it=="TLSv1.2"};require(protocols.isNotEmpty()){"PACKET_DNS_TLS_PROTOCOL"}
                    socket.enabledProtocols=protocols.toTypedArray();socket.soTimeout=scope.remaining();socket.startHandshake();scope.remaining()
                    if(endpoint.type=="https")http(socket,endpoint,query,scope) else framed(socket,query,scope)
                }
            }
        }
    private fun framed(socket:Socket,query:ByteArray,scope:Scope):ByteArray {
        socket.soTimeout=scope.remaining();val output=DataOutputStream(socket.getOutputStream());output.writeShort(query.size);output.write(query);output.flush()
        val input=socket.getInputStream();val prefix=read(input,2,socket,scope);val length=((prefix[0].toInt()and 255)shl 8)or(prefix[1].toInt()and 255);require(length in 12..65535){"PACKET_DNS_FRAME"}
        return read(input,length,socket,scope)
    }
    private fun read(input:InputStream,size:Int,socket:Socket,scope:Scope):ByteArray {
        require(size in 0..65535);val result=ByteArray(size);var at=0
        while(at<size){socket.soTimeout=scope.remaining();val count=input.read(result,at,size-at);if(count<0)throw IOException("PACKET_DNS_EOF");at+=count}
        scope.remaining();return result
    }
    private fun line(input:InputStream,socket:Socket,scope:Scope,max:Int):String {
        val bytes=ByteArrayOutputStream()
        while(bytes.size()<max){socket.soTimeout=scope.remaining();val value=input.read();if(value<0)throw IOException("PACKET_DNS_HTTP_EOF");bytes.write(value)
            if(value==10){val data=bytes.toByteArray();require(data.size>=2&&data[data.size-2]==13.toByte()){"PACKET_DNS_HTTP_CRLF"};return String(data,0,data.size-2,Charsets.US_ASCII)}
            require(value in 32..126||value==9||value==13){"PACKET_DNS_HTTP_ASCII"}
        }
        throw IOException("PACKET_DNS_HTTP_HEADER_BOUND")
    }
    private fun http(socket:Socket,endpoint:PacketManagedDnsPlan.Endpoint,query:ByteArray,scope:Scope):ByteArray {
        val host=if(':' in endpoint.tlsName)"[${endpoint.tlsName}]" else endpoint.tlsName
        val authority=host+if(endpoint.port==443)"" else ":${endpoint.port}"
        val header="POST ${endpoint.path} HTTP/1.1\r\nHost: $authority\r\nContent-Type: application/dns-message\r\nAccept: application/dns-message\r\nAccept-Encoding: identity\r\nContent-Length: ${query.size}\r\nConnection: close\r\n\r\n"
        socket.soTimeout=scope.remaining();socket.getOutputStream().apply {write(header.toByteArray(Charsets.US_ASCII));write(query);flush()}
        val input=socket.getInputStream();val status=line(input,socket,scope,1024)
        require(status.startsWith("HTTP/1.1 200 ")||status=="HTTP/1.1 200"||status.startsWith("HTTP/1.0 200 ")){"PACKET_DNS_DOH_STATUS"}
        val fields=linkedMapOf<String,String>();var bytes=status.length
        while(true){val value=line(input,socket,scope,8192);bytes+=value.length+2;require(bytes<=16384&&fields.size<=64){"PACKET_DNS_DOH_HEADERS"};if(value.isEmpty())break
            require(!value.first().isWhitespace()&&':' in value){"PACKET_DNS_DOH_FIELD"}
            val key=value.substringBefore(':').lowercase(Locale.ROOT);val data=value.substringAfter(':').trim()
            require(key.matches(Regex("[a-z0-9-]+"))&&fields.put(key,data)==null){"PACKET_DNS_DOH_DUPLICATE"}
        }
        require(fields["content-type"]?.substringBefore(';')?.trim()?.equals("application/dns-message",true)==true&&fields["content-encoding"].let {it==null||it.equals("identity",true)}){"PACKET_DNS_DOH_CONTENT_TYPE"}
        val transfer=fields["transfer-encoding"];val length=fields["content-length"]
        require(transfer==null||length==null){"PACKET_DNS_DOH_FRAMING"}
        if(transfer!=null){
            require(transfer.equals("chunked",true)){"PACKET_DNS_DOH_TRANSFER"};val output=ByteArrayOutputStream();var chunks=0
            while(true){require(chunks++<4096){"PACKET_DNS_DOH_CHUNKS"};val text=line(input,socket,scope,128)
                val hex=text.substringBefore(';');require(hex.matches(Regex("[0-9a-fA-F]{1,8}"))){"PACKET_DNS_DOH_CHUNK_SIZE"};val size=hex.toLong(16);require(size<=65535-output.size()){"PACKET_DNS_DOH_BODY_BOUND"}
                if(size==0L){var tail=0;while(true){val trailer=line(input,socket,scope,8192);tail+=trailer.length+2;require(tail<=8192);if(trailer.isEmpty())break};return output.toByteArray()}
                output.write(read(input,size.toInt(),socket,scope));require(read(input,2,socket,scope).contentEquals(byteArrayOf(13,10))){"PACKET_DNS_DOH_CHUNK_CRLF"}
            }
        }
        if(length!=null){require(length.matches(Regex("[0-9]{1,5}"))&&length.toInt() in 12..65535){"PACKET_DNS_DOH_LENGTH"};return read(input,length.toInt(),socket,scope)}
        val output=ByteArrayOutputStream();val block=ByteArray(4096)
        while(true){socket.soTimeout=scope.remaining();val count=input.read(block);if(count<0)break;require(output.size()+count<=65535){"PACKET_DNS_DOH_BODY_BOUND"};output.write(block,0,count)}
        return output.toByteArray()
    }
}
