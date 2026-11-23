package org.mcrf.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsSocksConnectTest {
    private class MemorySocket(bytes:ByteArray):Socket() {
        val output=ByteArrayOutputStream()
        private val input=ByteArrayInputStream(bytes)
        override fun getInputStream()=input
        override fun getOutputStream()=output
        override fun setSoTimeout(timeout:Int)=Unit
    }
    private val success=byteArrayOf(5,2,1,0,5,0,0,1,127,0,0,1,0,80)
    @Test fun authenticatedConnectKeepsHostnameRemoteAndUsesHttpsPort() {
        val socket=MemorySocket(success)
        DiagnosticsSocksConnect.connect(socket,"github.com","user","password",DiagnosticCancellation(1000))
        val expected=byteArrayOf(5,1,2,1,4)+"user".toByteArray()+byteArrayOf(8)+"password".toByteArray()+
            byteArrayOf(5,1,0,3,10)+"github.com".toByteArray()+byteArrayOf(1,0xbb.toByte())
        assertArrayEquals(expected,socket.output.toByteArray())
    }
    @Test fun forcedIpv6UsesNumericSocksAddressWithoutHostnameLookup() {
        val socket=MemorySocket(success)
        DiagnosticsSocksConnect.connect(socket,"[2001:db8::1]","u","p",DiagnosticCancellation(1000))
        val request=socket.output.toByteArray()
        assertEquals(4,request[11].toInt());assertEquals(30,request.size)
    }
    @Test fun unauthenticatedOrTruncatedRepliesCannotBecomeSuccess() {
        for(reply in listOf(byteArrayOf(5,0),byteArrayOf(5,2,1,1),success.dropLast(1).toByteArray())) {
            try {DiagnosticsSocksConnect.connect(MemorySocket(reply),"github.com","u","p",DiagnosticCancellation(1000));fail()}
            catch(_:java.io.IOException){}
        }
    }
    @Test fun cancelledProbeSendsNoAuthenticationBytes() {
        val socket=MemorySocket(success);val token=DiagnosticCancellation(1000);token.cancel()
        try {DiagnosticsSocksConnect.connect(socket,"github.com","u","p",token);fail()}
        catch(_:java.util.concurrent.CancellationException){}
        assertEquals(0,socket.output.size())
    }
    @Test fun authenticationConnectAndProtocolFailuresRemainDistinct() {
        val replies=listOf(
            byteArrayOf(5,0) to DiagnosticProxyFailure.AUTHENTICATION,
            byteArrayOf(5,2,1,1) to DiagnosticProxyFailure.AUTHENTICATION,
            byteArrayOf(5,2,1,0,5,5) to DiagnosticProxyFailure.CONNECT_REJECTED,
            success.dropLast(1).toByteArray() to DiagnosticProxyFailure.PROTOCOL)
        for((reply,expected)in replies) {
            try {DiagnosticsSocksConnect.connect(MemorySocket(reply),"github.com","u","p",DiagnosticCancellation(1000));fail()}
            catch(error:DiagnosticProxyException){assertEquals(expected,error.failure)}
        }
    }
}
