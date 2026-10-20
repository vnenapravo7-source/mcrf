package org.mcrf.client

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ServerManagementOutputTest {
    @Test fun largeResponseUsesBoundedBulkReadsAndPreservesEveryByte() {
        val expected="MCRF_MANAGEMENT|"+"x".repeat(250000)+"\n"
        var reads=0
        val input=object:ByteArrayInputStream(expected.toByteArray()) {
            override fun read():Int=error("Single-byte SSH output reads are forbidden")
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                assertTrue(length<=8192);reads++;return super.read(buffer,offset,length)
            }
        }
        val output=ServerManagementOutput();output.drain(input){}
        assertEquals(expected,output.utf8());assertTrue(reads in 1..32)
        assertEquals("ServerManagementOutput(redacted)",output.toString())
    }
    @Test fun cancellationBeforeReadOrAfterReadNeverAcceptsNewBytes() {
        for(cancelAt in listOf(1,2)) {
            val output=ServerManagementOutput();val input=ByteArrayInputStream("owned".toByteArray());var calls=0
            assertThrows(InterruptedException::class.java){output.drain(input){if(++calls==cancelAt)throw InterruptedException("owned cancellation")}}
            assertEquals(0,output.size)
        }
    }
    @Test fun responseLimitIsExactAndOverflowDoesNotGrowTheBuffer() {
        val output=ServerManagementOutput(4)
        output.drain(ByteArrayInputStream("1234".toByteArray())){}
        assertEquals("1234",output.utf8())
        assertThrows(IllegalStateException::class.java){output.drain(ByteArrayInputStream(byteArrayOf(5))) {}}
        assertEquals(4,output.size);assertEquals("1234",output.utf8())
    }
    @Test fun eofAndZeroReadAreNotBytesAndDoNotSpin() {
        for(result in listOf(-1,0)) {
            var reads=0
            val input=object:InputStream() {
                override fun available()=1
                override fun read():Int=error("Single byte read")
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {reads++;return result}
            }
            val output=ServerManagementOutput();output.drain(input){}
            assertEquals(1,reads);assertEquals(0,output.size)
        }
    }
    @Test fun fragmentedArrivalPreservesUtf8AcrossDrainBoundaries() {
        val bytes="Клиент ✓".toByteArray(Charsets.UTF_8);val output=ServerManagementOutput()
        bytes.forEach {output.drain(ByteArrayInputStream(byteArrayOf(it))) {}}
        assertEquals("Клиент ✓",output.utf8())
    }
    @Test fun callbackIsCheckedBetweenEveryChunk() {
        val input=ByteArrayInputStream(ByteArray(20000){1});val output=ServerManagementOutput();var checks=0
        assertThrows(IllegalStateException::class.java){output.drain(input){if(++checks==3)error("owned deadline")}}
        assertEquals(8192,output.size)
        assertEquals(20000-8192,input.available())
    }
}
