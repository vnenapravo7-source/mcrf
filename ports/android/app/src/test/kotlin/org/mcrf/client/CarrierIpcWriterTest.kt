package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CarrierIpcWriterTest {
    @Test fun knownDeadChildClosesWriterWithoutSendingStop() {
        val output=ByteArrayOutputStream();val writer=CarrierIpcWriter(output)
        writer.childExited()
        val completion=writer.awaitCompletion(5000)
        assertTrue(completion.writerExited);assertFalse(completion.stopWritten)
        assertFalse(completion.failed);assertEquals(0,output.size())
    }
    private fun command(value:String)="CAPTCHA_RESULT|$value\n".toByteArray(Charsets.UTF_8)
    private class Blocked:OutputStream() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val received=ByteArrayOutputStream()
        override fun write(value:Int){error("Array write expected")}
        override fun write(value:ByteArray){entered.countDown();check(release.await(5,TimeUnit.SECONDS));received.write(value)}
    }
    @Test fun fifoAndReservedStopCloseAdmissionWithoutNativeDeathClaim() {
        val stream=ByteArrayOutputStream();val writer=CarrierIpcWriter(stream)
        assertTrue(writer.offer(command("one")));assertTrue(writer.offer(command("two")))
        writer.requestStop();writer.requestStop();assertFalse(writer.offer(command("late")))
        val result=writer.awaitCompletion(5000)
        assertTrue(result.writerExited);assertTrue(result.stopWritten);assertFalse(result.failed)
        assertEquals("CAPTCHA_RESULT|one\nCAPTCHA_RESULT|two\nSTOP\n",stream.toString("UTF-8"))
        assertEquals("CarrierIpcWriter(redacted)",writer.toString())
    }
    @Test fun blockedPipeDoesNotBlockStopAdmissionOrInventCompletion() {
        val stream=Blocked();val writer=CarrierIpcWriter(stream,1)
        try {
            assertTrue(writer.offer(command("owned")));assertTrue(stream.entered.await(5,TimeUnit.SECONDS))
            assertFalse(writer.offer(command("over-capacity")))
            writer.requestStop()
            val pending=writer.awaitCompletion(0)
            assertFalse(pending.writerExited);assertFalse(pending.stopWritten);assertFalse(pending.failed)
            assertFalse(writer.offer(command("late")))
        }finally{stream.release.countDown();writer.requestStop();assertTrue(writer.awaitCompletion(5000).writerExited)}
        assertEquals("CAPTCHA_RESULT|owned\nSTOP\n",stream.received.toString("UTF-8"))
    }
    @Test fun callerMutationCannotChangeQueuedCommand() {
        val stream=Blocked();val writer=CarrierIpcWriter(stream)
        try {
            val proof=command("owned-proof")
            assertTrue(writer.offer(proof));assertTrue(stream.entered.await(5,TimeUnit.SECONDS))
            proof.fill(0);writer.requestStop();stream.release.countDown()
            assertTrue(writer.awaitCompletion(5000).writerExited)
            assertEquals("CAPTCHA_RESULT|owned-proof\nSTOP\n",stream.received.toString("UTF-8"))
        }finally{stream.release.countDown();writer.requestStop();writer.awaitCompletion(5000)}
    }
    @Test fun byteBudgetIncludesTheInFlightCommand() {
        val stream=Blocked();val writer=CarrierIpcWriter(stream,32,16384)
        try {
            val big=ByteArray(16384){65}.also {it[it.lastIndex]=10}
            assertTrue(writer.offer(big));assertTrue(stream.entered.await(5,TimeUnit.SECONDS))
            assertFalse(writer.offer(command("more")))
        }finally{stream.release.countDown();writer.requestStop();assertTrue(writer.awaitCompletion(5000).writerExited)}
    }
    @Test fun failedWriteIsNeverRetriedAndCannotConfirmStop() {
        var writes=0
        val stream=object:OutputStream(){override fun write(value:Int){error("Array write expected")};override fun write(value:ByteArray){writes++;throw IOException("owned failure")}}
        val writer=CarrierIpcWriter(stream)
        writer.offer(command("owned"));writer.requestStop()
        val result=writer.awaitCompletion(5000)
        assertTrue(result.writerExited);assertTrue(result.failed);assertFalse(result.stopWritten);assertEquals(1,writes)
        assertFalse(writer.offer(command("retry")))
    }
    @Test fun blockedCloseIsNotReportedAsWriterExit() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val stream=object:ByteArrayOutputStream(){override fun close(){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
        val writer=CarrierIpcWriter(stream)
        try {
            writer.requestStop();assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertFalse(writer.awaitCompletion(0).writerExited)
        }finally{release.countDown();assertTrue(writer.awaitCompletion(5000).writerExited)}
    }
    @Test fun writerOwnedProofBufferIsWipedAfterFailure() {
        var seen:ByteArray?=null
        val stream=object:OutputStream(){override fun write(value:Int){error("Array write expected")};override fun write(value:ByteArray){seen=value;throw IOException("owned failure")}}
        val writer=CarrierIpcWriter(stream)
        assertTrue(writer.offer(command("owned-sensitive-proof")));writer.requestStop()
        assertTrue(writer.awaitCompletion(5000).writerExited)
        assertTrue(requireNotNull(seen).all {it==0.toByte()})
    }
    @Test fun invalidAndOversizedFramesDoNotReachThePipe() {
        val stream=ByteArrayOutputStream();val writer=CarrierIpcWriter(stream)
        try {
            for(value in listOf(byteArrayOf(),byteArrayOf(65),ByteArray(16385){10})){
                assertThrows(IllegalArgumentException::class.java){writer.offer(value)}
            }
        }finally{writer.requestStop();assertTrue(writer.awaitCompletion(5000).writerExited)}
        assertEquals("STOP\n",stream.toString("UTF-8"))
    }
}
