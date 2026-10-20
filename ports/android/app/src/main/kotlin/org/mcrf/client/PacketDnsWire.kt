package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.util.Locale

/** Bounded DNS wire validation/cache/synthetic replies, not an OS resolver. No IO. */
object PacketDnsWire {
    class Question internal constructor(val name:String,val type:Int,val id:Int,val flags:Int,val end:Int,val udpLimit:Int) {
        fun cacheKey():String="$type:$name"
        override fun toString()="PacketDnsQuestion(redacted)"
    }
    class Reply internal constructor(val bytes:ByteArray,val ttlOffsets:List<Int>,val cacheSeconds:Int,val truncated:Boolean)
    fun u16(bytes:ByteArray,at:Int):Int {require(at>=0&&at+2<=bytes.size){"PACKET_DNS_WIRE_BOUND"};return ((bytes[at].toInt()and 255)shl 8)or(bytes[at+1].toInt()and 255)}
    private fun u32(bytes:ByteArray,at:Int):Long=(u16(bytes,at).toLong()shl 16)or u16(bytes,at+2).toLong()
    private fun name(bytes:ByteArray,start:Int):Pair<String,Int> {
        var at=start;var end=-1;val seen=hashSetOf<Int>();val labels=mutableListOf<String>();var length=0
        while(true){
            require(at in bytes.indices&&seen.add(at)&&seen.size<=128){"PACKET_DNS_NAME_BOUND"}
            val size=bytes[at].toInt()and 255
            if(size and 0xc0==0xc0){require(at+1<bytes.size);if(end<0)end=at+2;at=((size and 63)shl 8)or(bytes[at+1].toInt()and 255);continue}
            require(size<=63){"PACKET_DNS_LABEL_TYPE"};at++
            if(size==0){if(end<0)end=at;break}
            require(at+size<=bytes.size){"PACKET_DNS_LABEL_BOUND"}
            val label=bytes.copyOfRange(at,at+size)
            require(label.all {val ch=it.toInt()and 255;ch in 33..126&&ch!=46}){"PACKET_DNS_LABEL_ASCII"}
            labels+=label.toString(Charsets.US_ASCII).lowercase(Locale.ROOT);length+=size+1
            require(length<=254&&labels.size<=127){"PACKET_DNS_NAME_BOUND"};at+=size
        }
        return labels.joinToString(".") to end
    }
    fun question(bytes:ByteArray,response:Boolean=false):Question {
        require(bytes.size in 12..65535&&u16(bytes,4)==1){"PACKET_DNS_QUESTION_COUNT"}
        val flags=u16(bytes,2);require((flags and 0x8000!=0)==response&&flags and 0x7800==0){"PACKET_DNS_OPCODE"}
        val (name,end)=name(bytes,12);val type=u16(bytes,end)
        require(u16(bytes,end+2)==1){"PACKET_DNS_CLASS"}
        var udp=512
        if(!response){
            require(u16(bytes,6)==0&&u16(bytes,8)==0&&u16(bytes,10)<=16){"PACKET_DNS_QUERY_SECTIONS"}
            var at=end+4
            repeat(u16(bytes,10)){
                val (_,next)=name(bytes,at);require(next+10<=bytes.size)
                val kind=u16(bytes,next);val size=u16(bytes,next+8)
                require(next+10+size<=bytes.size){"PACKET_DNS_ADDITIONAL_BOUND"}
                if(kind==41)udp=u16(bytes,next+2).coerceIn(512,65535)
                at=next+10+size
            }
            require(at==bytes.size){"PACKET_DNS_QUERY_TAIL"}
        }
        return Question(name,type,u16(bytes,0),flags,end+4,udp)
    }
    fun response(query:ByteArray,bytes:ByteArray):Reply {
        val expected=question(query);val actual=question(bytes,true)
        require(expected.id==actual.id&&expected.type==actual.type&&expected.name==actual.name){"PACKET_DNS_RESPONSE_BINDING"}
        val truncated=actual.flags and 0x0200!=0
        // A truncated UDP reply only authorizes retry over the SAME endpoint's
        // TCP transport. Its incomplete records must never be cached/returned.
        if(truncated)return Reply(bytes.clone(),emptyList(),0,true)
        val records=u16(bytes,6)+u16(bytes,8)+u16(bytes,10);require(records<=4096){"PACKET_DNS_RECORD_BOUND"}
        var at=actual.end;var ttl=Long.MAX_VALUE;val offsets=mutableListOf<Int>()
        repeat(records){
            val (_,next)=name(bytes,at);require(next+10<=bytes.size){"PACKET_DNS_RECORD_HEADER"}
            val kind=u16(bytes,next);val size=u16(bytes,next+8);require(next+10+size<=bytes.size){"PACKET_DNS_RECORD_DATA"}
            if(kind!=41){offsets+=next+4;ttl=minOf(ttl,u32(bytes,next+4))}
            at=next+10+size
        }
        require(at==bytes.size){"PACKET_DNS_RESPONSE_TAIL"}
        val cache=if(!truncated&&actual.flags and 15==0&&u16(bytes,6)>0&&ttl!=Long.MAX_VALUE)ttl.coerceAtMost(86400).toInt() else 0
        return Reply(bytes.clone(),java.util.Collections.unmodifiableList(offsets),cache,truncated)
    }
    fun reply(query:ByteArray,rcode:Int,addresses:List<InetAddress> = emptyList(),truncated:Boolean=false):ByteArray {
        val q=question(query);require(rcode in 0..15&&addresses.size<=64)
        require(addresses.all {it.address.size==(if(q.type==1)4 else if(q.type==28)16 else 0)}){"PACKET_DNS_SYNTHETIC_FAMILY"}
        val output=ByteArrayOutputStream()
        DataOutputStream(output).apply {
            writeShort(q.id);writeShort(0x8080 or(q.flags and 0x0110)or rcode or(if(truncated)0x0200 else 0))
            writeShort(1);writeShort(addresses.size);writeInt(0)
            if(q.name.isNotEmpty())q.name.split('.').forEach {val value=it.toByteArray(Charsets.US_ASCII);writeByte(value.size);write(value)}
            writeByte(0);writeShort(q.type);writeShort(1)
            addresses.forEach {writeShort(0xc00c);writeShort(q.type);writeShort(1);writeInt(60);writeShort(it.address.size);write(it.address)}
        }
        return output.toByteArray()
    }
    fun query(name:String,type:Int,id:Int):ByteArray {
        require(name.length in 1..253&&type in setOf(1,28)&&id in 0..65535)
        return ByteArrayOutputStream().also {bytes->DataOutputStream(bytes).apply {
            writeShort(id);writeShort(0x0100);writeShort(1);repeat(3){writeShort(0)}
            name.split('.').forEach {label->val value=label.toByteArray(Charsets.US_ASCII);require(value.size in 1..63&&value.all {it.toInt() in 33..126&&it!=46.toByte()});writeByte(value.size);write(value)}
            writeByte(0);writeShort(type);writeShort(1)
        }}.toByteArray()
    }
    fun addresses(query:ByteArray,reply:ByteArray):List<InetAddress> {
        val validated=response(query,reply);val q=question(reply,true);if(q.flags and 15!=0||validated.truncated)return emptyList()
        var at=q.end;val addresses=mutableListOf<InetAddress>()
        repeat(u16(reply,6)){
            val (_,next)=name(reply,at);val kind=u16(reply,next);val size=u16(reply,next+8);val start=next+10
            if(u16(reply,next+2)==1&&kind==q.type&&(kind==1&&size==4||kind==28&&size==16))addresses+=InetAddress.getByAddress(reply.copyOfRange(start,start+size))
            at=start+size
        }
        return addresses.distinct().take(8)
    }
    fun cached(reply:Reply,query:ByteArray,elapsedSeconds:Int):ByteArray {
        require(elapsedSeconds>=0&&elapsedSeconds<reply.cacheSeconds)
        val copy=reply.bytes.clone();copy[0]=query[0];copy[1]=query[1]
        for(at in reply.ttlOffsets){val ttl=(u32(copy,at)-elapsedSeconds).coerceAtLeast(0);repeat(4){index->copy[at+index]=(ttl ushr(24-index*8)).toByte()}}
        return copy
    }
    fun cacheKey(query:ByteArray):String=java.security.MessageDigest.getInstance("SHA-256").digest(query.copyOfRange(2,query.size)).joinToString(""){"%02x".format(it.toInt()and 255)}
}
