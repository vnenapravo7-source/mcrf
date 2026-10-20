package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class PacketFixtureDnsTest {
    private val nonce="a".repeat(32)
    private fun reply(q:ByteArray,v6:Boolean):ByteArray {
        val header=q.copyOfRange(0,12);header[2]=0x81.toByte();header[3]=0x80.toByte();header[7]=1
        val ip=if(v6)byteArrayOf(0xfd.toByte(),0x77,0,0,0,0,0,0,0,0,0,0,0,0,0,1)else byteArrayOf(10,77,0,1)
        return header+q.copyOfRange(12,q.size)+byteArrayOf(0xc0.toByte(),12,0,if(v6)28 else 1,0,1,0,0,0,0,0,ip.size.toByte())+ip
    }
    @Test fun validatesBothAddressFamiliesAndTransactionIds(){for(v6 in listOf(false,true))for(id in listOf(0,65535,1234)){
        val q=PacketFixtureDns.query(nonce,v6,id);assertEquals(58,q.size);PacketFixtureDns.verify(q,reply(q,v6),v6)
    }}
    @Test fun rejectsEveryChangedResponseByte(){for(v6 in listOf(false,true)){
        val q=PacketFixtureDns.query(nonce,v6,42);val r=reply(q,v6)
        for(i in r.indices){val corrupt=r.clone();corrupt[i]=(corrupt[i].toInt() xor 1).toByte();assertThrows(IllegalArgumentException::class.java){PacketFixtureDns.verify(q,corrupt,v6)}}
    }}
    @Test fun rejectsTruncationAndTrailingBytes(){val q=PacketFixtureDns.query(nonce,false,42);val r=reply(q,false)
        for(n in 0 until r.size)assertThrows(IllegalArgumentException::class.java){PacketFixtureDns.verify(q,r.copyOf(n),false)}
        assertThrows(IllegalArgumentException::class.java){PacketFixtureDns.verify(q,r+byteArrayOf(0),false)}
    }
    @Test fun rejectsUnscopedNamesAndInvalidIds(){for(name in listOf("example.com","A".repeat(32),"a".repeat(31),nonce+".invalid")){
        assertThrows(IllegalArgumentException::class.java){PacketFixtureDns.query(name,false,1)}
    };for(id in listOf(-1,65536))assertThrows(IllegalArgumentException::class.java){PacketFixtureDns.query(nonce,false,id)}}
}
