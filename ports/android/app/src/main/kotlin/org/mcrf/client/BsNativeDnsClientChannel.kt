package org.mcrf.client

import java.net.InetAddress
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Mapping is NOT ownership authority. The listener must freshly verify the
 * original tuple with Android before DNS selection and immediately before reply. */
internal class BsNativeDnsClientChannel(
    private val generation:Long,private val live:()->Boolean,
    private val allowPolicyDestinations:Boolean=false,private val send:(ByteArray)->Boolean
):Closeable {
    data class Client(val protocol:Int,val source:InetAddress,val sourcePort:Int,val destination:InetAddress){
        override fun toString()="BsDnsClient(redacted)"
    }
    private class Pending(val protocol:Int){val ready=CountDownLatch(1);var value:Client?=null}
    private val lock=Any();private val pending=hashMapOf<Long,Pending>();private var serial=0L;private var closed=false
    init{require(generation>0)}
    fun query(protocol:Int,translatedPort:Int):Client {
        require(protocol in setOf(6,17)&&translatedPort in 1..65535)
        val request=Pending(protocol)
        val id=synchronized(lock){
            check(!closed&&live()&&pending.size<16&&serial<Long.MAX_VALUE)
            val id=++serial;pending[id]=request
            if(!send("BS_DNS_CLIENT|$generation|$id|$protocol|$translatedPort\n".toByteArray(Charsets.US_ASCII))){pending.remove(id);throw IOException("BS_DNS_MAPPING_PIPE")};id
        }
        try{
            if(!request.ready.await(2,TimeUnit.SECONDS))throw IOException("BS_DNS_MAPPING_TIMEOUT")
            return synchronized(lock){if(closed||!live())throw IOException("BS_DNS_MAPPING_RETIRED");request.value?:throw IOException("BS_DNS_MAPPING_MISSING")}
        }finally{synchronized(lock){pending.remove(id)}}
    }
    fun answer(line:String):Boolean {
        if(!line.startsWith("MCRF_BS_DNS_CLIENT "))return false
        require(line.length<=256&&line.all{it.code in 32..126})
        val fields=line.removePrefix("MCRF_BS_DNS_CLIENT ").split(' ')
        require(fields.size in setOf(3,6)&&fields[0]==generation.toString()&&fields[1].matches(Regex("[1-9][0-9]{0,18}")))
        val id=requireNotNull(fields[1].toLongOrNull())
        val client=if(fields.size==3){require(fields[2]=="NONE");null}else{
            require(fields[2] in setOf("6","17")&&fields[4].matches(Regex("[1-9][0-9]{0,4}")))
            val source=requireNotNull(RouteRules.literal(fields[3]));val destination=requireNotNull(RouteRules.literal(fields[5]));val port=fields[4].toInt();require(port in 1..65535)
            require('%' !in fields[3]&&'%' !in fields[5]&&source.address.size==destination.address.size)
            require(fields[5] in setOf("198.19.255.53","fdfe:dcba:9876:dd54::53")||
                allowPolicyDestinations&&!destination.isAnyLocalAddress&&!destination.isLoopbackAddress&&!destination.isMulticastAddress&&!destination.isLinkLocalAddress)
            require(!source.isAnyLocalAddress&&!source.isMulticastAddress&&!source.isLoopbackAddress)
            Client(fields[2].toInt(),source,port,destination)
        }
        synchronized(lock){pending[id]?.let{require(it.ready.count>0&&(client==null||client.protocol==it.protocol));it.value=client;it.ready.countDown()}}
        return true
    }
    override fun close(){synchronized(lock){closed=true;pending.values.forEach{it.ready.countDown()};pending.clear()}}
    override fun toString()="BsNativeDnsClientChannel(redacted)"
}
