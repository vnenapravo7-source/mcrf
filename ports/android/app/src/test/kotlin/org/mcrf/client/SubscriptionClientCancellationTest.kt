package org.mcrf.client

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class SubscriptionClientCancellationTest {
    private val address="https://subscription.fixture.test/owned"
    private val profile="vless://11111111-1111-4111-8111-111111111111@127.0.0.1:443?security=tls#Owned"
    private class Connection(val body:InputStream,val status:()->Int={200},val headers:(String)->String?={null}):HttpURLConnection(URL("https://subscription.fixture.test/owned")) {
        var disconnected=false
        var bodyRequests=0
        override fun connect()=Unit
        override fun usingProxy()=false
        override fun disconnect(){disconnected=true}
        override fun getResponseCode()=status()
        override fun getContentLengthLong()=-1L
        override fun getInputStream():InputStream {bodyRequests++;return body}
        override fun getHeaderField(name:String)=headers(name)
    }
    private fun cancelled(block:()->Unit) {
        try {
            assertThrows(InterruptedException::class.java){block()}
            assertTrue(Thread.currentThread().isInterrupted)
        }finally{Thread.interrupted()}
    }
    private fun fetch(connection:Connection)=SubscriptionClient.fetch(address,SubscriptionClient.Opener {connection})
    @Test fun preInterruptedAttemptNeverOpensAConnection() {
        var opened=0;Thread.currentThread().interrupt()
        cancelled {SubscriptionClient.fetch(address,SubscriptionClient.Opener {opened++;error("Must not open")})}
        assertEquals(0,opened)
    }
    @Test fun interruptionWhileOpeningDisconnectsBeforeHeadersOrBody() {
        val connection=Connection(ByteArrayInputStream(profile.toByteArray()),status={error("Must not request response")})
        cancelled {SubscriptionClient.fetch(address,SubscriptionClient.Opener {Thread.currentThread().interrupt();connection})}
        assertTrue(connection.disconnected);assertEquals(0,connection.bodyRequests)
    }
    @Test fun interruptedRedirectNeverOpensTheNextDestination() {
        var opened=0
        val connection=Connection(ByteArrayInputStream(byteArrayOf()),status={Thread.currentThread().interrupt();302},headers={if(it=="Location")"https://other.fixture.test/owned"else null})
        cancelled {SubscriptionClient.fetch(address,SubscriptionClient.Opener {opened++;connection})}
        assertEquals(1,opened);assertTrue(connection.disconnected)
    }
    @Test fun interruptedChunkOrEofClosesStreamAndDoesNotReturnProfiles() {
        for(atEof in listOf(false,true)) {
            var closed=false
            val body=object:ByteArrayInputStream(profile.toByteArray()) {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    val count=super.read(buffer,offset,length)
                    if((count<0)==atEof)Thread.currentThread().interrupt()
                    return count
                }
                override fun close(){closed=true;super.close()}
            }
            val connection=Connection(body)
            cancelled {fetch(connection)}
            assertTrue(closed);assertTrue(connection.disconnected)
        }
    }
    @Test fun cancellationDuringMetadataStillRejectsTheCompletedResult() {
        val connection=Connection(ByteArrayInputStream(profile.toByteArray()),headers={if(it=="profile-title")Thread.currentThread().interrupt();null})
        cancelled {fetch(connection)}
        assertTrue(connection.disconnected)
    }
    @Test fun ordinaryCompletionAndTimeoutRetainTheirExistingMeaning() {
        val connection=Connection(ByteArrayInputStream(profile.toByteArray()))
        assertEquals(1,fetch(connection).servers.size);assertTrue(connection.disconnected)
        val timeout=Connection(ByteArrayInputStream(byteArrayOf()),status={throw SocketTimeoutException("owned timeout")})
        val error=assertThrows(IllegalArgumentException::class.java){fetch(timeout)}
        assertTrue(error.message!!.contains("30"));assertFalse(Thread.currentThread().isInterrupted)
        assertTrue(timeout.disconnected)
    }
}
