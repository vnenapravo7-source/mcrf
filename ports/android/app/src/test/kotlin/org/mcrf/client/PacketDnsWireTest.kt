package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/** Queued with the whole P1 package; this is not a receipt of execution. */
class PacketDnsWireTest {
    private fun address(v6:Boolean)=InetAddress.getByAddress(if(v6)byteArrayOf(0xfd.toByte(),0x77,0,0,0,0,0,0,0,0,0,0,0,0,0,1)else byteArrayOf(10,77,0,1))
    @Test fun bothFamiliesBindQuestionIdAndCacheTtlWithoutChangingBuffers(){
        for(v6 in listOf(false,true)){
            val query=PacketDnsWire.query("owned.invalid",if(v6)28 else 1,123)
            val answer=PacketDnsWire.reply(query,0,listOf(address(v6)));val before=answer.clone()
            val validated=PacketDnsWire.response(query,answer)
            assertEquals(listOf(address(v6)),PacketDnsWire.addresses(query,answer));assertEquals(60,validated.cacheSeconds)
            val changed=PacketDnsWire.query("owned.invalid",if(v6)28 else 1,456)
            val cached=PacketDnsWire.cached(validated,changed,17)
            assertEquals(456,PacketDnsWire.question(cached,true).id);assertEquals(43,PacketDnsWire.response(changed,cached).cacheSeconds)
            assertArrayEquals(before,answer);assertEquals(PacketDnsWire.cacheKey(query),PacketDnsWire.cacheKey(changed))
            assertThrows(IllegalArgumentException::class.java){PacketDnsWire.cached(validated,changed,60)}
        }
    }
    @Test fun noDataTruncatedAndFailureAreNotCached(){
        val query=PacketDnsWire.query("owned.invalid",28,1)
        for(answer in listOf(PacketDnsWire.reply(query,0),PacketDnsWire.reply(query,2),PacketDnsWire.reply(query,0,truncated=true))){
            assertEquals(0,PacketDnsWire.response(query,answer).cacheSeconds);assertTrue(PacketDnsWire.addresses(query,answer).isEmpty())
        }
        assertTrue(PacketDnsWire.response(query,PacketDnsWire.reply(query,0,truncated=true)).truncated)
    }
    @Test fun mismatchedIdNameTypeAndPointerCyclesFailClosed(){
        val query=PacketDnsWire.query("owned.invalid",1,123)
        for(other in listOf(PacketDnsWire.query("owned.invalid",1,124),PacketDnsWire.query("other.invalid",1,123),PacketDnsWire.query("owned.invalid",28,123))){
            assertThrows(IllegalArgumentException::class.java){PacketDnsWire.response(query,PacketDnsWire.reply(other,0))}
        }
        val cycle=query.clone();cycle[12]=0xc0.toByte();cycle[13]=12
        assertThrows(IllegalArgumentException::class.java){PacketDnsWire.question(cycle)}
        assertThrows(IllegalArgumentException::class.java){PacketDnsWire.question(query+byteArrayOf(0))}
        assertThrows(IllegalArgumentException::class.java){PacketDnsWire.reply(query,0,listOf(address(true)))}
    }
    @Test fun cacheDistinguishesDnssecAndOriginalQuestionCase(){
        val plain=PacketDnsWire.query("owned.invalid",1,7)
        val cd=plain.clone().also {it[3]=(it[3].toInt()or 0x10).toByte()}
        val upper=plain.clone().also {it[13]='O'.code.toByte()}
        assertNotEquals(PacketDnsWire.cacheKey(plain),PacketDnsWire.cacheKey(cd))
        assertNotEquals(PacketDnsWire.cacheKey(plain),PacketDnsWire.cacheKey(upper))
        assertEquals(PacketDnsWire.question(plain).name,PacketDnsWire.question(upper).name)
    }
    @Test fun bootstrapDoesNotAcceptAnOppositeFamilyAnswer(){
        val a=PacketDnsWire.query("owned.invalid",1,7);val aaaa=PacketDnsWire.query("owned.invalid",28,7)
        val foreign=PacketDnsWire.reply(aaaa,0,listOf(address(true)))
        // Same question name and ID, but an AAAA record attached to an A query.
        val reply=PacketDnsWire.reply(a,0)+foreign.copyOfRange(PacketDnsWire.question(foreign,true).end,foreign.size)
        reply[7]=1
        assertTrue(PacketDnsWire.addresses(a,reply).isEmpty())
    }
}
